package com.capstone.tracking.review;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.review.dto.ReviewCloneRequest;
import com.capstone.tracking.review.dto.ReviewResultRequest;
import com.capstone.tracking.review.dto.ReviewScheduleRequest;
import com.capstone.tracking.review.dto.ReviewSessionResponse;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Giai đoạn 5 — Reviews 1, 2 and 3. An Admin schedules each group's review (time, room, panel); Review 2 can be cloned
 * from Review 1. The panel records the result; for Review 3 (the closed council: 3 lecturers, one chair) the chair's
 * result sorts the group into Defense 1, revise-then-Defense 1, or Defense 2.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    static final int CLOSED_COUNCIL_SIZE = 3;
    /** Longest review we expect; bounds the overlap query window. */
    private static final Duration MAX_SESSION = Duration.ofHours(8);

    private final ReviewSessionRepository sessionRepository;
    private final StudentGroupService studentGroupService;
    private final UserService userService;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final com.capstone.tracking.scheduling.ScheduleGuard scheduleGuard;
    private final com.capstone.tracking.semester.SemesterCalendarService calendars;
    private final com.capstone.tracking.defense.DefenseSessionRepository defenses;

    @Transactional
    public ReviewSessionResponse schedule(ReviewScheduleRequest request, User actingUser) {
        scheduleGuard.acquire();
        StudentGroup group = studentGroupService.getById(request.groupId());
        boolean closedCouncil = request.round() == ReviewRound.REVIEW_3;
        PanelSelection panel = PanelSelection.resolve(userService, request.reviewerIds(), request.chairId(),
                closedCouncil ? CLOSED_COUNCIL_SIZE : null, closedCouncil);
        ReviewSession session = create(group, request.round(), request.scheduledAt(), request.durationMinutes(),
                request.location(), panel, actingUser);
        return ReviewSessionResponse.from(session);
    }

    /** Bước 5.2: Review 2's schedule is Review 1's, shifted; the panel carries over. */
    @Transactional
    public List<ReviewSessionResponse> cloneRound(ReviewCloneRequest request, User actingUser) {
        scheduleGuard.acquire();
        if (request.fromRound() == request.toRound()) {
            throw new BadRequestException("fromRound and toRound must differ");
        }
        if (request.toRound() == ReviewRound.REVIEW_3) {
            throw new BadRequestException("Review 3 (closed council) is scheduled per group with its own 3-member panel");
        }
        List<ReviewSessionResponse> created = new ArrayList<>();
        for (ReviewSession source : sessionRepository.findByRoundAndGroup_SemesterOrderByScheduledAtAsc(
                request.fromRound(), request.semester())) {
            if (sessionRepository.existsByGroupIdAndRound(source.getGroup().getId(), request.toRound())) {
                continue;
            }
            UUID chairId = source.chair().map(m -> m.getReviewer().getId()).orElse(null);
            PanelSelection panel = PanelSelection.resolve(userService,
                    source.getPanel().stream().map(m -> m.getReviewer().getId()).toList(), chairId, null, false);
            ReviewSession copy = create(source.getGroup(), request.toRound(),
                    source.getScheduledAt().plus(Duration.ofDays(request.offsetDays())),
                    source.getDurationMinutes(), source.getLocation(), panel, actingUser);
            created.add(ReviewSessionResponse.from(copy));
        }
        return created;
    }

    @Transactional
    public ReviewSessionResponse recordResult(UUID id, ReviewResultRequest request, User actingUser) {
        scheduleGuard.acquire();
        ReviewSession session = find(id);
        boolean closedCouncil = session.getRound() == ReviewRound.REVIEW_3;
        requirePanelAuthority(session, actingUser, closedCouncil);
        if (session.getCompletedAt() != null) {
            throw new ConflictException("The result of this review is already recorded");
        }
        if (closedCouncil && request.outcome() == null) {
            throw new BadRequestException("Review 3 needs an outcome: READY_FOR_DEFENSE_1, REVISE_BEFORE_DEFENSE_1 or DEFER_TO_DEFENSE_2");
        }
        if (closedCouncil && request.outcome() == ClosedCouncilOutcome.REVISE_BEFORE_DEFENSE_1
                && (request.revisionDeadline() == null || !request.revisionDeadline().isAfter(Instant.now()))) {
            throw new BadRequestException("A future revisionDeadline is required when revisions are requested");
        }

        session.setFeedback(request.feedback());
        session.setCompletedAt(Instant.now());
        session.setRecordedBy(actingUser);
        if (closedCouncil) {
            session.setOutcome(request.outcome());
            if (request.outcome() == ClosedCouncilOutcome.REVISE_BEFORE_DEFENSE_1) {
                session.setRevisionDeadline(request.revisionDeadline());
            }
        }

        String label = session.getRound().label() + (closedCouncil ? ": " + request.outcome().label() : "");
        auditService.record("ReviewSession", session.getId(), closedCouncil ? AuditAction.APPROVE : AuditAction.UPDATE,
                actingUser, outcomeDetails(session));
        events.publishEvent(DomainEvent.of(DomainEventType.REVIEW_RESULT, session.getGroup().getId(), session.getId(),
                actingUser.getId(), label).withDetails(request.feedback(), session.getRevisionDeadline()));
        return ReviewSessionResponse.from(session);
    }

    /** REVISE_BEFORE_DEFENSE_1: the chair, the group's supervisor or an Admin confirms the revision was done. */
    @Transactional
    public ReviewSessionResponse confirmRevision(UUID id, User actingUser) {
        scheduleGuard.acquire();
        ReviewSession session = find(id);
        if (session.getOutcome() != ClosedCouncilOutcome.REVISE_BEFORE_DEFENSE_1) {
            throw new ConflictException("Only a Review 3 result of REVISE_BEFORE_DEFENSE_1 needs a revision confirmation");
        }
        if (session.getRevisionCompletedAt() != null) {
            throw new ConflictException("The revision is already confirmed");
        }
        boolean chair = session.chair().map(m -> m.getReviewer().getId().equals(actingUser.getId())).orElse(false);
        StudentGroup group = session.getGroup();
        boolean supervisor = group.getSupervisor() != null && group.getSupervisor().getId().equals(actingUser.getId());
        if (!chair && !supervisor && actingUser.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only the council chair, the group's supervisor or an Admin can confirm the revision");
        }
        if (session.getRevisionDeadline() != null && !Instant.now().isBefore(session.getRevisionDeadline())) {
            throw new ConflictException("The revision deadline has passed; this group must go to Defense 2");
        }
        session.setRevisionCompletedAt(Instant.now());
        auditService.record("ReviewSession", session.getId(), AuditAction.APPROVE, actingUser, Map.of("revisionCompleted", true));
        events.publishEvent(DomainEvent.of(DomainEventType.REVIEW_RESULT, group.getId(), session.getId(),
                actingUser.getId(), "Review 3: đã hoàn thiện chỉnh sửa, được ra Bảo vệ lần 1"));
        return ReviewSessionResponse.from(session);
    }

    public List<ReviewSessionResponse> listByGroup(UUID groupId, User actingUser) {
        studentGroupService.getById(groupId);
        studentGroupService.requireCanView(groupId, actingUser);
        return sessionRepository.findByGroupIdOrderByScheduledAtAsc(groupId).stream().map(ReviewSessionResponse::from).toList();
    }

    public List<ReviewSessionResponse> listByRound(ReviewRound round, String semester) {
        List<ReviewSession> sessions = StringUtils.hasText(semester)
                ? sessionRepository.findByRoundAndGroup_SemesterOrderByScheduledAtAsc(round, semester)
                : sessionRepository.findByRoundOrderByScheduledAtAsc(round);
        return sessions.stream().map(ReviewSessionResponse::from).toList();
    }

    /** The reviews a lecturer sits on. */
    public List<ReviewSessionResponse> listMine(User actingUser) {
        return sessionRepository.findByReviewer(actingUser.getId()).stream().map(ReviewSessionResponse::from).toList();
    }

    public ReviewSessionResponse getById(UUID id, User actingUser) {
        ReviewSession session = find(id);
        studentGroupService.requireCanView(session.getGroup().getId(), actingUser);
        return ReviewSessionResponse.from(session);
    }

    /** Used by the defense module: the group's Review 3, if held. */
    public Optional<ReviewSession> closedCouncilOf(UUID groupId) {
        return sessionRepository.findByGroupIdAndRound(groupId, ReviewRound.REVIEW_3);
    }

    private ReviewSession create(StudentGroup group, ReviewRound round, Instant scheduledAt, int durationMinutes,
                                 String location, PanelSelection panel, User actingUser) {
        calendars.requireReviewWeek(group.getSemester(), round, scheduledAt);
        if (group.getTopic() == null) {
            throw new BadRequestException("Group " + group.getGroupCode() + " has no approved topic yet");
        }
        if (sessionRepository.existsByGroupIdAndRound(group.getId(), round)) {
            throw new ConflictException("Group " + group.getGroupCode() + " already has a " + round.label() + " scheduled");
        }
        Instant end = scheduledAt.plus(Duration.ofMinutes(durationMinutes));
        for (ReviewSession other : sessionRepository.findByScheduledAtLessThan(end)) {
            if (other.endsAt().isAfter(scheduledAt)
                    && (other.getLocation().equalsIgnoreCase(location.trim())
                        || other.getGroup().getId().equals(group.getId())
                        || other.getPanel().stream().anyMatch(m -> panel.ids().contains(m.getReviewer().getId())))) {
                throw new ConflictException("Room, group or reviewer already has a review at this time");
            }
        }
        for (var other : defenses.findByScheduledAtLessThan(end)) {
            if (other.endsAt().isAfter(scheduledAt)
                    && (other.getRoom().equalsIgnoreCase(location.trim())
                        || other.getGroup().getId().equals(group.getId())
                        || other.getCommittee().stream().anyMatch(m -> panel.ids().contains(m.getMember().getId())))) {
                throw new ConflictException("Room, group or reviewer already has a defense at this time");
            }
        }

        ReviewSession session = ReviewSession.builder()
                .group(group)
                .round(round)
                .scheduledAt(scheduledAt)
                .durationMinutes(durationMinutes)
                .location(location.trim())
                .build();
        for (User reviewer : panel.members()) {
            session.getPanel().add(ReviewPanelMember.builder()
                    .session(session).reviewer(reviewer).chair(panel.isChair(reviewer)).build());
        }
        session = sessionRepository.save(session);

        auditService.record("ReviewSession", session.getId(), AuditAction.CREATE, actingUser,
                Map.of("groupId", group.getId(), "round", round, "reviewers", panel.ids()));
        events.publishEvent(DomainEvent.of(DomainEventType.REVIEW_SCHEDULED, group.getId(), session.getId(),
                        actingUser.getId(), round.label() + " lúc " + VnTime.format(scheduledAt) + " tại " + location)
                .withDetails("Hội đồng: " + panel.describe(), scheduledAt));
        return session;
    }

    /** Reviews 1-2: any panel member. Review 3: the chair. Admin always. */
    private void requirePanelAuthority(ReviewSession session, User actingUser, boolean chairOnly) {
        if (actingUser.getRole() == Role.ADMIN) {
            return;
        }
        boolean allowed = chairOnly
                ? session.chair().map(m -> m.getReviewer().getId().equals(actingUser.getId())).orElse(false)
                : session.hasReviewer(actingUser);
        if (!allowed) {
            throw new AccessDeniedException(chairOnly
                    ? "Only the council chair records the Review 3 result"
                    : "Only a member of this review panel can record its result");
        }
    }

    private static Map<String, Object> outcomeDetails(ReviewSession session) {
        Map<String, Object> details = new HashMap<>();
        details.put("round", session.getRound());
        if (session.getOutcome() != null) {
            details.put("outcome", session.getOutcome());
        }
        return details;
    }

    private ReviewSession find(UUID id) {
        return sessionRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("ReviewSession", id));
    }
}
