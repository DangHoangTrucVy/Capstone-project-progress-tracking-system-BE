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
 * YC17: before the roster is Locked a member asks to leave and the Leader approves. After Locked the change goes
 * through the supervisor and an Admin instead (see {@link StudentGroupService#removeMember}). The Leader cannot leave
 * this way; an Admin replaces the Leader first (YC20).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupLeaveService {

    private final MemberLeaveRequestRepository leaveRequests;
    private final StudentGroupService groups;
    private final ApplicationEventPublisher events;

    @Transactional
    public MemberLeaveRequest request(UUID groupId, User current, String reason) {
        StudentGroup group = groups.lockById(groupId);
        if (!groups.isActiveMember(groupId, current.getId())) {
            throw new AccessDeniedException("You are not an active member of this group");
        }
        if (groups.listActiveMembers(groupId).stream()
                .anyMatch(m -> m.isLeader() && m.getUser().getId().equals(current.getId()))) {
            throw new BadRequestException("The leader cannot leave directly; ask an administrator to replace the leader");
        }
        groups.requireNotLocked(group);
        if (leaveRequests.findByGroupIdAndUserIdAndStatus(groupId, current.getId(), LeaveRequestStatus.PENDING).isPresent()) {
            throw new ConflictException("You already asked to leave this group");
        }
        MemberLeaveRequest saved = leaveRequests.save(MemberLeaveRequest.builder()
                .group(group)
                .user(current)
                .reason(reason == null || reason.isBlank() ? null : reason.trim())
                .build());
        events.publishEvent(DomainEvent.of(DomainEventType.LEAVE_REQUESTED, groupId, saved.getId(), current.getId(),
                current.getFullName()).withDetails(saved.getReason(), null));
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

    /** Leader approves: the member leaves and may join another group afterwards (YC21). */
    @Transactional
    public MemberLeaveRequest approve(UUID id, User leader) {
        MemberLeaveRequest request = lock(id);
        StudentGroup group = groups.lockById(request.getGroup().getId());
        groups.requireActiveLeader(group.getId(), leader);
        requirePending(request);
        groups.requireNotLocked(group);
        decide(request, LeaveRequestStatus.APPROVED, leader);
        groups.removeActiveMember(group.getId(), request.getUser().getId());
        events.publishEvent(DomainEvent.of(DomainEventType.LEAVE_DECIDED, group.getId(), request.getId(),
                leader.getId(), "đã được chấp nhận").withTarget(request.getUser().getId()));
        events.publishEvent(DomainEvent.of(DomainEventType.MEMBER_LEFT, group.getId(), request.getId(),
                leader.getId(), request.getUser().getFullName()));
        return request;
    }

    @Transactional
    public MemberLeaveRequest reject(UUID id, User leader) {
        MemberLeaveRequest request = lock(id);
        groups.requireActiveLeader(request.getGroup().getId(), leader);
        requirePending(request);
        events.publishEvent(DomainEvent.of(DomainEventType.LEAVE_DECIDED, request.getGroup().getId(), request.getId(),
                leader.getId(), "bị từ chối").withTarget(request.getUser().getId()));
        return decide(request, LeaveRequestStatus.REJECTED, leader);
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
        boolean leader = groups.listActiveMembers(groupId).stream()
                .anyMatch(m -> m.isLeader() && m.getUser().getId().equals(viewer.getId()));
        return leader ? all : all.stream().filter(r -> r.getUser().getId().equals(viewer.getId())).toList();
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
