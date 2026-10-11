package com.capstone.tracking.group;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * YC17: before the roster is Locked, and within the semester's permitted period ({@link FormationWindow}), a member
 * asks to leave and the Leader approves. After Locked the change goes
 * through the supervisor and an Admin instead (see {@link StudentGroupService#removeMember}). The Leader's own request
 * goes to the Admins (YC20): they are notified, and on approval they name the next Leader or the earliest-joined
 * member takes over. An Admin may also decide any member's request.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupLeaveService {

    private final MemberLeaveRequestRepository leaveRequests;
    private final StudentGroupService groups;
    private final FormationWindow window;
    private final ApplicationEventPublisher events;

    @Transactional
    public MemberLeaveRequest request(UUID groupId, User current, String reason) {
        StudentGroup group = groups.lockById(groupId);
        if (!groups.isActiveMember(groupId, current.getId())) {
            throw new AccessDeniedException("You are not an active member of this group");
        }
        boolean leader = isLeader(groupId, current);
        if (leader && (reason == null || reason.isBlank())) {
            // The Admin decides the Leader's request and must know why (LEA-05).
            throw new BadRequestException("Say why you want to leave the group");
        }
        if (!leader) {
            // The Leader's request is decided by an Admin, who may change even a Locked roster.
            groups.requireNotLocked(group);
            window.requireOpen(group.getSemester());
        }
        if (leaveRequests.findByGroupIdAndUserIdAndStatus(groupId, current.getId(), LeaveRequestStatus.PENDING).isPresent()) {
            throw new ConflictException("You already asked to leave this group");
        }
        MemberLeaveRequest saved = leaveRequests.save(MemberLeaveRequest.builder()
                .group(group)
                .user(current)
                .reason(reason == null || reason.isBlank() ? null : reason.trim())
                .leaderRequest(leader)
                .build());
        events.publishEvent(DomainEvent.of(leader ? DomainEventType.LEADER_LEAVE_REQUESTED : DomainEventType.LEAVE_REQUESTED,
                groupId, saved.getId(), current.getId(), current.getFullName()).withDetails(saved.getReason(), null));
        return saved;
    }

    @Transactional
    public MemberLeaveRequest withdraw(UUID id, User current) {
        MemberLeaveRequest request = lock(id);
        if (!request.getUser().getId().equals(current.getId())) {
            throw new AccessDeniedException("This is not your request");
        }
        requirePending(request);
        request.setStatus(LeaveRequestStatus.WITHDRAWN);
        request.setDecidedAt(Instant.now());
        return request;
    }

    @Transactional
    public MemberLeaveRequest approve(UUID id, User actor) {
        return approve(id, actor, null);
    }

    /**
     * The Leader (or an Admin) approves a member's request: the member leaves and may join another group afterwards
     * (YC21). A Leader's own request only an Admin approves; {@code newLeaderUserId} names the member who takes over,
     * otherwise the earliest-joined remaining member does.
     */
    @Transactional
    public MemberLeaveRequest approve(UUID id, User actor, UUID newLeaderUserId) {
        MemberLeaveRequest request = lock(id);
        StudentGroup group = groups.lockById(request.getGroup().getId());
        requireDecider(request, actor);
        requirePending(request);
        if (actor.getRole() != Role.ADMIN) {
            groups.requireNotLocked(group);
            window.requireOpen(group.getSemester());
        }
        UUID leaving = request.getUser().getId();
        decide(request, LeaveRequestStatus.APPROVED, actor);
        if (request.isLeaderRequest() && newLeaderUserId != null) {
            if (newLeaderUserId.equals(leaving)) {
                throw new BadRequestException("The new leader must be another member of the group");
            }
            groups.replaceLeader(group.getId(), newLeaderUserId);
        }
        groups.removeActiveMember(group.getId(), leaving);
        if (request.isLeaderRequest()) {
            groups.promoteSuccessorIfLeaderless(group.getId());
        }
        events.publishEvent(DomainEvent.of(DomainEventType.LEAVE_DECIDED, group.getId(), request.getId(),
                actor.getId(), "đã được chấp nhận").withTarget(leaving));
        events.publishEvent(DomainEvent.of(DomainEventType.MEMBER_LEFT, group.getId(), request.getId(),
                actor.getId(), request.getUser().getFullName()));
        return request;
    }

    @Transactional
    public MemberLeaveRequest reject(UUID id, User actor) {
        MemberLeaveRequest request = lock(id);
        requireDecider(request, actor);
        requirePending(request);
        events.publishEvent(DomainEvent.of(DomainEventType.LEAVE_DECIDED, request.getGroup().getId(), request.getId(),
                actor.getId(), "bị từ chối").withTarget(request.getUser().getId()));
        return decide(request, LeaveRequestStatus.REJECTED, actor);
    }

    /** Admin inbox: every leave request, optionally only one status and/or only the Leaders' own requests. */
    public List<MemberLeaveRequest> listAll(LeaveRequestStatus status, boolean leaderOnly) {
        return leaveRequests.search(status, leaderOnly);
    }

    /** An Admin decides any request; the group's Leader decides the members' requests, not their own. */
    private void requireDecider(MemberLeaveRequest request, User actor) {
        if (actor.getRole() == Role.ADMIN) {
            return;
        }
        if (request.isLeaderRequest()) {
            throw new AccessDeniedException("A leader's request to leave is decided by an administrator");
        }
        groups.requireActiveLeader(request.getGroup().getId(), actor);
    }

    private boolean isLeader(UUID groupId, User user) {
        return groups.listActiveMembers(groupId).stream()
                .anyMatch(m -> m.isLeader() && m.getUser().getId().equals(user.getId()));
    }

    /** The Leader and Admin see every request of the group; a member sees only their own. */
    public List<MemberLeaveRequest> list(UUID groupId, User viewer) {
        List<MemberLeaveRequest> all = leaveRequests.findByGroupIdOrderByCreatedAtDesc(groupId);
        if (viewer.getRole() == Role.ADMIN) {
            return all;
        }
        if (!groups.isActiveMember(groupId, viewer.getId())) {
            throw new AccessDeniedException("You are not an active member of this group");
        }
        return isLeader(groupId, viewer) ? all : all.stream().filter(r -> r.getUser().getId().equals(viewer.getId())).toList();
    }

    private MemberLeaveRequest decide(MemberLeaveRequest request, LeaveRequestStatus status, User by) {
        request.setStatus(status);
        request.setDecidedBy(by);
        request.setDecidedAt(Instant.now());
        return request;
    }

    private MemberLeaveRequest lock(UUID id) {
        return leaveRequests.lockById(id).orElseThrow(() -> ResourceNotFoundException.of("LeaveRequest", id));
    }

    private void requirePending(MemberLeaveRequest request) {
        if (request.getStatus() != LeaveRequestStatus.PENDING) {
            throw new ConflictException("This leave request is already " + request.getStatus().name().toLowerCase());
        }
    }
}
