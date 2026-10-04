package com.capstone.tracking.group;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.eligibility.EligibilityService;
import com.capstone.tracking.group.dto.JoinRequestResponse;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Apply / Invite flow (YC08-YC15, YC22).
 *
 * <ul>
 *   <li>A student Applies; the Leader reviews it and, to accept, sends an Invite. Approving an Apply does not make the
 *       student a member (YC10).</li>
 *   <li>The student becomes an official member the moment they Accept an Invite (YC11); that is also when every other
 *       open request of theirs is cancelled (YC13) and capacity is checked again under a lock (YC15).</li>
 *   <li>At most {@value #MAX_OPEN_APPLICATIONS} open Applies per student; Invites are unlimited and never hold a
 *       seat (YC08, YC09, YC15).</li>
 *   <li>Requests expire after the semester's TTL (48 h by default) and can be withdrawn by their sender (YC14).</li>
 * </ul>
 *
 * Lock order is always student, then request, then group, so concurrent Accepts cannot deadlock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupJoinService {

    public static final int MAX_OPEN_APPLICATIONS = 3;

    /** Applications whose applicant details the group may still see (YC22). */
    private static final Set<JoinRequestStatus> VISIBLE_TO_GROUP =
            Set.of(JoinRequestStatus.PENDING, JoinRequestStatus.APPROVED, JoinRequestStatus.ACCEPTED);

    private final GroupJoinRequestRepository requests;
    private final GroupApplicationVoteRepository votes;
    private final FormationWindow window;
    private final GroupMemberRepository members;
    private final StudentGroupService groups;
    private final EligibilityService eligibility;
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    // ------------------------------------------------------------------ settings (YC14)

    public int ttlHours(String semester) {
        return window.ttlHours(semester);
    }

    @Transactional
    public int setTtlHours(String semester, int hours) {
        return window.setTtlHours(semester, hours);
    }

    /** Writes down requests whose deadline has passed; effective status is also computed on read. */
    @Scheduled(initialDelayString = "${app.groups.expire-delay-ms:60000}",
            fixedDelayString = "${app.groups.expire-delay-ms:60000}")
    @Transactional
    public void expireDue() {
        int expired = requests.expireDue(Instant.now());
        if (expired > 0) {
            log.info("Expired {} unanswered group Apply/Invite requests", expired);
        }
    }

    // ------------------------------------------------------------------ Apply

    @Transactional
    public GroupJoinRequest apply(UUID groupId, User current, String message) {
        User student = users.lockById(current.getId()).orElseThrow();
        requireStudent(student);
        eligibility.requireEligible(student);

        StudentGroup group = groups.getById(groupId);
        window.requireOpen(group.getSemester());
        requireNoGroup(student, group.getSemester(), "You already belong to a group");
        groups.requireRecruiting(group);
        requireRoom(group);

        Instant now = Instant.now();
        clearExpiredDuplicate(groupId, student.getId(), JoinRequestType.APPLY, now, "You already applied to this group");
        if (requests.countOpen(student.getId(), JoinRequestType.APPLY, now) >= MAX_OPEN_APPLICATIONS) {
            throw new ConflictException("You can have at most " + MAX_OPEN_APPLICATIONS
                    + " pending applications; withdraw one or wait for an answer");
        }
        GroupJoinRequest saved = requests.save(GroupJoinRequest.builder()
                .group(group)
                .student(student)
                .type(JoinRequestType.APPLY)
                .message(blankToNull(message))
                .createdBy(student)
                .expiresAt(now.plus(Duration.ofHours(ttlHours(group.getSemester()))))
                .build());
        events.publishEvent(DomainEvent.of(DomainEventType.JOIN_APPLICATION_RECEIVED, group.getId(), saved.getId(),
                student.getId(), student.getFullName()));
        return saved;
    }

    @Transactional
    public GroupJoinRequest withdrawApplication(UUID id, User current) {
        GroupJoinRequest app = lock(id, JoinRequestType.APPLY);
        if (!app.getStudent().getId().equals(current.getId())) {
            throw new AccessDeniedException("This is not your application");
        }
        requirePending(app);
        return close(app, JoinRequestStatus.WITHDRAWN);
    }

    /** Leader accepts the Apply by inviting the applicant; the applicant is still not a member until they Accept. */
    @Transactional
    public GroupJoinRequest approveApplication(UUID id, User leader) {
        GroupJoinRequest app = lock(id, JoinRequestType.APPLY);
        StudentGroup group = groups.lockById(app.getGroup().getId());
        groups.requireActiveLeader(group.getId(), leader);
        requirePending(app);
        window.requireOpen(group.getSemester());
        groups.requireRecruiting(group);
        requireRoom(group);
        User student = app.getStudent();
        eligibility.requireEligible(student);
        requireNoGroup(student, group.getSemester(), "The applicant already belongs to a group");

        Instant now = Instant.now();
        GroupJoinRequest invite = requests.findPending(group.getId(), student.getId(), JoinRequestType.INVITE)
                .filter(i -> i.getExpiresAt().isAfter(now))
                .orElse(null);
        if (invite == null) {
            invite = newInvite(group, student, leader, app.getMessage(), app.getId(), now);
        }
        close(app, JoinRequestStatus.APPROVED);
        return invite;
    }

    @Transactional
    public GroupJoinRequest rejectApplication(UUID id, User leader) {
        GroupJoinRequest app = lock(id, JoinRequestType.APPLY);
        groups.lockById(app.getGroup().getId());
        groups.requireActiveLeader(app.getGroup().getId(), leader);
        requirePending(app);
        events.publishEvent(DomainEvent.of(DomainEventType.JOIN_APPLICATION_REJECTED, app.getGroup().getId(),
                app.getId(), leader.getId(), app.getGroup().getGroupCode()).withTarget(app.getStudent().getId()));
        return close(app, JoinRequestStatus.REJECTED);
    }

    // ------------------------------------------------------------------ Invite

    /** Leader invites a student directly (YC11); it does not matter whether they applied. */
    @Transactional
    public GroupJoinRequest sendInvite(UUID groupId, User leader, UUID userId, String email, String identifier,
                                       String message) {
        StudentGroup group = groups.lockById(groupId);
        groups.requireActiveLeader(groupId, leader);
        window.requireOpen(group.getSemester());
        groups.requireRecruiting(group);
        requireRoom(group);

        User student = groups.resolveStudent(userId, email, identifier);
        requireStudent(student);
        eligibility.requireEligible(student);
        requireNoGroup(student, group.getSemester(), "This student already belongs to a group");

        Instant now = Instant.now();
        clearExpiredDuplicate(groupId, student.getId(), JoinRequestType.INVITE, now,
                "This student already has a pending invitation from your group");
        return newInvite(group, student, leader, message, null, now);
    }

    /** The moment the student becomes an official member (YC11). */
    @Transactional
    public GroupMember acceptInvite(UUID id, User current) {
        User student = users.lockById(current.getId()).orElseThrow();
        GroupJoinRequest invite = lock(id, JoinRequestType.INVITE);
        if (!invite.getStudent().getId().equals(student.getId())) {
            throw new AccessDeniedException("This invitation is not addressed to you");
        }
        requirePending(invite);
        eligibility.requireEligible(student);
        StudentGroup group = groups.lockById(invite.getGroup().getId());
        window.requireOpen(group.getSemester());
        groups.requireRecruiting(group);
        // Capacity and "one official group" are checked again inside enroll, under both locks (YC15).
        invite.setStatus(JoinRequestStatus.ACCEPTED);
        invite.setRespondedAt(Instant.now());
        GroupMember member = groups.enroll(group, student, false, invite.getId());
        events.publishEvent(DomainEvent.of(DomainEventType.MEMBER_JOINED, group.getId(), invite.getId(),
                student.getId(), student.getFullName()));
        return member;
    }

    @Transactional
    public GroupJoinRequest declineInvite(UUID id, User current) {
        GroupJoinRequest invite = lock(id, JoinRequestType.INVITE);
        if (!invite.getStudent().getId().equals(current.getId())) {
            throw new AccessDeniedException("This invitation is not addressed to you");
        }
        requirePending(invite);
        events.publishEvent(DomainEvent.of(DomainEventType.JOIN_INVITE_DECLINED, invite.getGroup().getId(),
                invite.getId(), current.getId(), invite.getStudent().getFullName()));
        return close(invite, JoinRequestStatus.REJECTED);
    }

    /** The group's leader takes an unanswered Invite back. */
    @Transactional
    public GroupJoinRequest revokeInvite(UUID id, User leader) {
        GroupJoinRequest invite = lock(id, JoinRequestType.INVITE);
        groups.requireActiveLeader(invite.getGroup().getId(), leader);
        requirePending(invite);
        return close(invite, JoinRequestStatus.WITHDRAWN);
    }

    // ------------------------------------------------------------------ advisory votes (YC12)

    /** A member's reference opinion about an applicant; the Leader decides without waiting for votes. */
    @Transactional
    public GroupApplicationVote vote(UUID applicationId, User voter, VoteType type, String comment) {
        GroupJoinRequest app = requests.findById(applicationId)
                .filter(r -> r.getType() == JoinRequestType.APPLY)
                .orElseThrow(() -> ResourceNotFoundException.of("Application", applicationId));
        if (!groups.isActiveMember(app.getGroup().getId(), voter.getId())) {
            throw new AccessDeniedException("Only members of the group can give an opinion");
        }
        requirePending(app);
        GroupApplicationVote vote = votes.findByApplicationIdAndVoterId(applicationId, voter.getId())
                .orElseGet(() -> GroupApplicationVote.builder().application(app).voter(voter).build());
        vote.setVote(type);
        vote.setComment(blankToNull(comment));
        return votes.save(vote);
    }

    public List<GroupApplicationVote> listVotes(UUID applicationId, User viewer) {
        GroupJoinRequest app = requests.findById(applicationId)
                .filter(r -> r.getType() == JoinRequestType.APPLY)
                .orElseThrow(() -> ResourceNotFoundException.of("Application", applicationId));
        requireMemberOrAdmin(app.getGroup().getId(), viewer);
        return votes.findByApplicationIdOrderByCreatedAtAsc(applicationId);
    }

    // ------------------------------------------------------------------ queries

    public List<JoinRequestResponse> listForGroup(UUID groupId, JoinRequestType type, User viewer) {
        requireMemberOrAdmin(groupId, viewer);
        return requests.findByGroupIdAndTypeOrderByCreatedAtDesc(groupId, type).stream()
                .map(r -> toResponse(r, viewer)).toList();
    }

    public List<JoinRequestResponse> listMine(JoinRequestType type, User viewer) {
        return requests.findByStudentIdAndTypeOrderByCreatedAtDesc(viewer.getId(), type).stream()
                .map(r -> toResponse(r, viewer)).toList();
    }

    public JoinRequestResponse toResponse(GroupJoinRequest r, User viewer) {
        Instant now = Instant.now();
        boolean applicant = r.getStudent().getId().equals(viewer.getId());
        boolean admin = viewer.getRole() == Role.ADMIN;
        boolean groupMember = !applicant && !admin && groups.isActiveMember(r.getGroup().getId(), viewer.getId());
        // YC22: Leader and members see the applicant's profile only while the Apply is alive; after it is rejected,
        // withdrawn or expired that right ends. An Invite's target was chosen by the leader, so it stays visible.
        boolean showStudent = applicant || admin
                || (groupMember && (r.getType() == JoinRequestType.INVITE || applicationAlive(r, now)));
        // The private part of the profile (bio, skills) is for recruiting: on an Invite it is shown while the
        // Invite is open or accepted; once declined, revoked or expired only the identity the leader typed remains.
        boolean showPrivate = applicant || admin
                || (r.getType() == JoinRequestType.INVITE ? VISIBLE_TO_GROUP.contains(r.effectiveStatus(now)) : showStudent);
        Integer support = null;
        Integer oppose = null;
        if ((groupMember || admin) && r.getType() == JoinRequestType.APPLY) {
            List<GroupApplicationVote> tally = votes.findByApplicationIdOrderByCreatedAtAsc(r.getId());
            support = (int) tally.stream().filter(v -> v.getVote() == VoteType.SUPPORT).count();
            oppose = tally.size() - support;
        }
        return JoinRequestResponse.from(r, now, showStudent, showPrivate, support, oppose);
    }

    /**
     * YC22: the group may see an applicant while the Apply is pending. An approved Apply stays visible only as long as
     * the Invite it produced is still open or was accepted; if that Invite is declined, revoked or expires, the right
     * that came from the Apply ends too.
     */
    private boolean applicationAlive(GroupJoinRequest app, Instant now) {
        JoinRequestStatus status = app.effectiveStatus(now);
        if (status == JoinRequestStatus.PENDING) {
            return true;
        }
        if (status != JoinRequestStatus.APPROVED) {
            return false;
        }
        return requests.findBySourceApplicationIdAndType(app.getId(), JoinRequestType.INVITE).stream()
                .map(i -> i.effectiveStatus(now))
                .anyMatch(st -> st == JoinRequestStatus.PENDING || st == JoinRequestStatus.ACCEPTED);
    }

    // ------------------------------------------------------------------ helpers

    private GroupJoinRequest newInvite(StudentGroup group, User student, User sender, String message,
                                       UUID sourceApplicationId, Instant now) {
        GroupJoinRequest saved = requests.save(GroupJoinRequest.builder()
                .group(group)
                .student(student)
                .type(JoinRequestType.INVITE)
                .message(blankToNull(message))
                .createdBy(sender)
                .sourceApplicationId(sourceApplicationId)
                .expiresAt(now.plus(Duration.ofHours(ttlHours(group.getSemester()))))
                .build());
        events.publishEvent(DomainEvent.of(DomainEventType.JOIN_INVITE_RECEIVED, group.getId(), saved.getId(),
                sender.getId(), group.getGroupCode()).withTarget(student.getId()));
        return saved;
    }

    /** An open request of the same kind blocks a new one, but a dead (expired, not yet swept) one does not. */
    private void clearExpiredDuplicate(UUID groupId, UUID studentId, JoinRequestType type, Instant now, String conflict) {
        requests.findPending(groupId, studentId, type).ifPresent(existing -> {
            if (existing.getExpiresAt().isAfter(now)) {
                throw new ConflictException(conflict);
            }
            existing.setStatus(JoinRequestStatus.EXPIRED);
            existing.setRespondedAt(now);
            requests.saveAndFlush(existing);
        });
    }

    private GroupJoinRequest lock(UUID id, JoinRequestType type) {
        return requests.lockById(id).filter(r -> r.getType() == type)
                .orElseThrow(() -> ResourceNotFoundException.of(type == JoinRequestType.APPLY ? "Application" : "Invite", id));
    }

    private void requirePending(GroupJoinRequest request) {
        JoinRequestStatus status = request.effectiveStatus(Instant.now());
        if (status != JoinRequestStatus.PENDING) {
            throw new ConflictException("This " + request.getType().name().toLowerCase() + " is already "
                    + status.name().toLowerCase());
        }
    }

    private GroupJoinRequest close(GroupJoinRequest request, JoinRequestStatus status) {
        request.setStatus(status);
        request.setRespondedAt(Instant.now());
        return request;
    }

    private void requireStudent(User user) {
        if (user.getRole() != Role.STUDENT && user.getRole() != Role.GROUP_LEADER) {
            throw new BadRequestException("Only student accounts can apply to or be invited to a group");
        }
    }

    /** YC13: one official group per capstone round; while in one, no new Apply and no new Invite. */
    private void requireNoGroup(User student, String semester, String conflict) {
        if (members.existsActiveInSemester(student.getId(), semester)) {
            throw new ConflictException(conflict);
        }
    }

    private void requireRoom(StudentGroup group) {
        if (groups.countActiveMembers(group.getId()) >= StudentGroupService.MAX_MEMBERS) {
            throw new ConflictException("The group is full: a group can have at most "
                    + StudentGroupService.MAX_MEMBERS + " members");
        }
    }

    private void requireMemberOrAdmin(UUID groupId, User viewer) {
        if (viewer.getRole() != Role.ADMIN && !groups.isActiveMember(groupId, viewer.getId())) {
            throw new AccessDeniedException("You are not an active member of this group");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
