package com.capstone.tracking.proposal;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.proposal.dto.ProposalDecisionRequest;
import com.capstone.tracking.proposal.dto.ProposalForwardRequest;
import com.capstone.tracking.proposal.dto.ProposalRoundRequest;
import com.capstone.tracking.proposal.dto.ProposalRoundResponse;
import com.capstone.tracking.proposal.dto.ProposalSubmitRequest;
import com.capstone.tracking.proposal.dto.TopicProposalResponse;
import com.capstone.tracking.topic.Topic;
import com.capstone.tracking.topic.TopicRepository;
import com.capstone.tracking.topic.TopicStatus;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Giai đoạn 2 — topic registration and approval, at most {@link ProposalPolicy#MAX_ROUNDS} council reviews:
 * the Leader submits the list, the group's supervisor forwards one topic, the Council approves (the topic becomes the
 * group's official Topic) or rejects with feedback; rounds 2-4 need a window an Admin opened. Returns DTOs because
 * the responses read lazy associations (open-in-view is off).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TopicProposalService {

    private static final Set<ProposalStatus> IN_PROGRESS =
            EnumSet.of(ProposalStatus.PENDING_INSTRUCTOR, ProposalStatus.PENDING_COUNCIL);

    private final TopicProposalRepository proposalRepository;
    private final ProposalRoundRepository roundRepository;
    private final TopicRepository topicRepository;
    private final StudentGroupService studentGroupService;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    // --- Leader --------------------------------------------------------------------------------

    @Transactional
    public TopicProposalResponse submit(UUID groupId, ProposalSubmitRequest request, User actingUser) {
        StudentGroup group = studentGroupService.getById(groupId);
        studentGroupService.requireActiveLeader(groupId, actingUser);
        if (group.getSupervisor() == null) {
            throw new BadRequestException("The group needs a supervisor before it can submit topics for pre-review");
        }
        if (proposalRepository.existsByGroupIdAndStatusIn(groupId, IN_PROGRESS)) {
            throw new ConflictException("The group already has a topic proposal under review");
        }
        if (proposalRepository.existsByGroupIdAndStatusIn(groupId, EnumSet.of(ProposalStatus.APPROVED))) {
            throw new ConflictException("The group's topic has already been approved");
        }

        int round = (int) proposalRepository.countByGroupId(groupId) + 1;
        if (round > ProposalPolicy.MAX_ROUNDS) {
            throw new ConflictException("The group has used all " + ProposalPolicy.MAX_ROUNDS + " topic review rounds");
        }
        int count = request.topics().size();
        if (round == 1 && count != ProposalPolicy.FIRST_ROUND_TOPIC_COUNT) {
            throw new BadRequestException("The first submission must list exactly "
                    + ProposalPolicy.FIRST_ROUND_TOPIC_COUNT + " topics (got " + count + ")");
        }
        boolean windowOpen = round == 1 || roundRepository.findBySemesterAndRoundNumber(group.getSemester(), round)
                .map(r -> r.isOpenAt(Instant.now()))
                .orElse(false);
        if (!windowOpen) {
            throw new BadRequestException("Submission round " + round + " is not open for " + group.getSemester()
                    + "; wait for the Admin to open it");
        }

        TopicProposal proposal = TopicProposal.builder()
                .group(group)
                .round(round)
                .status(ProposalStatus.PENDING_INSTRUCTOR)
                .submittedBy(actingUser)
                .submittedAt(Instant.now())
                .build();
        for (int i = 0; i < count; i++) {
            ProposalSubmitRequest.Item item = request.topics().get(i);
            proposal.getItems().add(TopicProposalItem.builder()
                    .proposal(proposal).title(item.title().trim()).description(item.description())
                    .sortOrder(i).selected(false).build());
        }
        proposal = proposalRepository.save(proposal);

        auditService.record("TopicProposal", proposal.getId(), AuditAction.CREATE, actingUser,
                Map.of("groupId", groupId, "round", round, "topics", count));
        events.publishEvent(DomainEvent.of(DomainEventType.TOPIC_PROPOSAL_SUBMITTED, groupId, proposal.getId(),
                actingUser.getId(), "lần " + round));
        return TopicProposalResponse.from(proposal);
    }

    // --- Instructor ----------------------------------------------------------------------------

    @Transactional
    public TopicProposalResponse forward(UUID id, ProposalForwardRequest request, User actingUser) {
        TopicProposal proposal = find(id);
        studentGroupService.requireSupervisorOrAdmin(proposal.getGroup(), actingUser);
        if (proposal.getStatus() != ProposalStatus.PENDING_INSTRUCTOR) {
            throw new ConflictException("Only a proposal waiting for pre-review can be forwarded (status: "
                    + proposal.getStatus() + ")");
        }
        TopicProposalItem item = proposal.getItems().stream()
                .filter(i -> i.getId().equals(request.itemId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Topic " + request.itemId() + " is not part of this proposal"));

        Instant now = Instant.now();
        item.setSelected(true);
        proposal.setInstructorNote(request.note());
        proposal.setForwardedBy(actingUser);
        proposal.setForwardedAt(now);
        proposal.setCouncilDeadline(now.plus(ProposalPolicy.councilReviewPeriod(proposal.getRound())));
        proposal.setStatus(ProposalStatus.PENDING_COUNCIL);

        auditService.record("TopicProposal", proposal.getId(), AuditAction.UPDATE, actingUser,
                Map.of("selectedItemId", item.getId(), "status", ProposalStatus.PENDING_COUNCIL));
        events.publishEvent(DomainEvent.of(DomainEventType.TOPIC_FORWARDED_TO_COUNCIL, proposal.getGroup().getId(),
                        proposal.getId(), actingUser.getId(), item.getTitle())
                .withDetails(request.note(), proposal.getCouncilDeadline()));
        return TopicProposalResponse.from(proposal);
    }

    // --- Council -------------------------------------------------------------------------------

    @Transactional
    public TopicProposalResponse decide(UUID id, ProposalDecisionRequest request, User actingUser) {
        TopicProposal proposal = find(id);
        if (proposal.getStatus() != ProposalStatus.PENDING_COUNCIL) {
            throw new ConflictException("Only a proposal forwarded to the Council can be decided (status: "
                    + proposal.getStatus() + ")");
        }
        boolean approved = request.decision() == ProposalDecisionRequest.Decision.APPROVED;
        if (!approved && !StringUtils.hasText(request.feedback())) {
            throw new BadRequestException("Feedback is required when rejecting a topic");
        }
        TopicProposalItem item = proposal.selectedItem()
                .orElseThrow(() -> new IllegalStateException("Forwarded proposal " + id + " has no selected topic"));
        StudentGroup group = proposal.getGroup();

        proposal.setDecidedBy(actingUser);
        proposal.setDecidedAt(Instant.now());
        proposal.setCouncilFeedback(request.feedback());

        DomainEvent event;
        if (approved) {
            proposal.setStatus(ProposalStatus.APPROVED);
            Topic topic = topicRepository.save(Topic.builder()
                    .topicCode(uniqueTopicCode(group.getGroupCode(), proposal.getRound()))
                    .title(item.getTitle())
                    .description(item.getDescription())
                    .admin(actingUser)
                    .status(TopicStatus.PUBLISHED)
                    .build());
            proposal.setApprovedTopic(topic);
            group.setTopic(topic);
            if (group.getStatus() == GroupStatus.FORMED) {
                group.setStatus(GroupStatus.ACTIVE);
            }
            event = DomainEvent.of(DomainEventType.TOPIC_APPROVED, group.getId(), proposal.getId(),
                    actingUser.getId(), item.getTitle()).withDetails(request.feedback(), null);
        } else {
            proposal.setStatus(ProposalStatus.REJECTED);
            int remaining = ProposalPolicy.MAX_ROUNDS - proposal.getRound();
            String details = request.feedback() + (remaining > 0
                    ? "\nNhóm còn " + remaining + " lần nộp lại; cổng nộp lần " + (proposal.getRound() + 1)
                            + " do Admin mở. Điều chỉnh đề tài cùng Giảng viên hướng dẫn."
                    : "\nNhóm đã dùng hết " + ProposalPolicy.MAX_ROUNDS + " lần duyệt đề tài.");
            event = DomainEvent.of(DomainEventType.TOPIC_REJECTED, group.getId(), proposal.getId(),
                    actingUser.getId(), item.getTitle()).withDetails(details, null);
        }

        auditService.record("TopicProposal", proposal.getId(), approved ? AuditAction.APPROVE : AuditAction.REJECT,
                actingUser, Map.of("round", proposal.getRound(), "itemId", item.getId()));
        events.publishEvent(event);
        return TopicProposalResponse.from(proposal);
    }

    // --- Reads ---------------------------------------------------------------------------------

    public List<TopicProposalResponse> listByGroup(UUID groupId, User actingUser) {
        studentGroupService.getById(groupId);
        studentGroupService.requireCanView(groupId, actingUser);
        return proposalRepository.findByGroupIdOrderByRoundAsc(groupId).stream().map(TopicProposalResponse::from).toList();
    }

    public TopicProposalResponse getById(UUID id, User actingUser) {
        TopicProposal proposal = find(id);
        studentGroupService.requireCanView(proposal.getGroup().getId(), actingUser);
        return TopicProposalResponse.from(proposal);
    }

    /** Review queue: an Instructor sees the groups they supervise, Council and Admin see every group. */
    public Page<TopicProposalResponse> queue(ProposalStatus status, User actingUser, Pageable pageable) {
        Set<ProposalStatus> statuses = status != null ? EnumSet.of(status) : EnumSet.allOf(ProposalStatus.class);
        Page<TopicProposal> page = actingUser.getRole() == Role.INSTRUCTOR
                ? proposalRepository.findByGroup_Supervisor_IdAndStatusIn(actingUser.getId(), statuses, pageable)
                : proposalRepository.findByStatusIn(statuses, pageable);
        return page.map(TopicProposalResponse::from);
    }

    // --- Admin: submission windows for rounds 2-4 -----------------------------------------------

    @Transactional
    public ProposalRoundResponse openRound(ProposalRoundRequest request, User actingUser) {
        Instant opensAt = request.opensAt() != null ? request.opensAt() : Instant.now();
        Instant closesAt = request.closesAt() != null ? request.closesAt() : opensAt.plus(ProposalPolicy.DEFAULT_WINDOW);
        if (!closesAt.isAfter(opensAt)) {
            throw new BadRequestException("closesAt must be after opensAt");
        }
        ProposalRound round = roundRepository.findBySemesterAndRoundNumber(request.semester(), request.roundNumber())
                .orElseGet(() -> ProposalRound.builder()
                        .semester(request.semester()).roundNumber(request.roundNumber()).build());
        round.setOpensAt(opensAt);
        round.setClosesAt(closesAt);
        round.setClosed(false);
        round.setOpenedBy(actingUser);
        round = roundRepository.save(round);

        auditService.record("ProposalRound", round.getId(), AuditAction.UPDATE, actingUser,
                Map.of("semester", request.semester(), "round", request.roundNumber(), "closesAt", closesAt.toString()));
        for (TopicProposal rejected : proposalRepository.findRejectedInRound(request.semester(), request.roundNumber() - 1)) {
            events.publishEvent(DomainEvent.of(DomainEventType.PROPOSAL_ROUND_OPENED, rejected.getGroup().getId(),
                    round.getId(), actingUser.getId(), "lần " + request.roundNumber())
                    .withDetails("Góp ý lần trước của Hội đồng: " + rejected.getCouncilFeedback(), closesAt));
        }
        return ProposalRoundResponse.from(round);
    }

    @Transactional
    public ProposalRoundResponse closeRound(UUID id, User actingUser) {
        ProposalRound round = roundRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("ProposalRound", id));
        round.setClosed(true);
        auditService.record("ProposalRound", round.getId(), AuditAction.UPDATE, actingUser, Map.of("closed", true));
        return ProposalRoundResponse.from(round);
    }

    public List<ProposalRoundResponse> listRounds(String semester) {
        List<ProposalRound> rounds = StringUtils.hasText(semester)
                ? roundRepository.findBySemesterOrderByRoundNumberAsc(semester)
                : roundRepository.findAllByOrderBySemesterAscRoundNumberAsc();
        return rounds.stream().map(ProposalRoundResponse::from).toList();
    }

    private TopicProposal find(UUID id) {
        return proposalRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("TopicProposal", id));
    }

    private String uniqueTopicCode(String groupCode, int round) {
        String base = groupCode + "-R" + round;
        String code = base;
        for (int n = 2; topicRepository.existsByTopicCodeIgnoreCase(code); n++) {
            code = base + "-" + n;
        }
        return code;
    }
}
