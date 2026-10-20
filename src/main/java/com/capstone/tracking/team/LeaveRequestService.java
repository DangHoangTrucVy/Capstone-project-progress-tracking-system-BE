package com.capstone.tracking.team;

import com.capstone.tracking.common.Locks;
import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.NoGroupReason;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.FormationScopeRepository;
import com.capstone.tracking.subject.ScopeRules;
import com.capstone.tracking.team.LeaveRequest.Status;
import com.capstone.tracking.team.dto.LeaveRequestResponse;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave requests and complaints (6.4, 7.1). The Leader of a locked group approves or refuses before the deadline; a
 * refused member may complain to the Moderator; after the deadline the Moderator decides every request. An approved
 * request takes the member out (a leaving Leader is replaced as in 6.5).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LeaveRequestService {

    private final LeaveRequestRepository leaveRequests;
    private final FormationScopeRepository scopes;
    private final GroupCore core;
    private final ScopeRules rules;
    private final Access access;
    private final Notifier notifier;
    private final AuditService audit;
    private final Locks locks;
    private final com.capstone.tracking.roster.StudentGate gate;

    @Transactional
    public LeaveRequest leaderDecide(UUID requestId, boolean approve, String note, User leader) {
        LeaveRequest snapshot = find(requestId);
        FormationScope scope = lockScopeShared(snapshot.getGroup().getScope().getId());
        core.lockEnrollment(snapshot.getEnrollment().getId());
        LeaveRequest request = lock(requestId);
        StudentGroup group = core.lock(request.getGroup().getId());
        core.requireLeader(group, leader.getId());
        gate.requireCanAct(scope.getSubject().getId(), leader);
        if (request.getStatus() != Status.PENDING_LEADER) {
            throw closed(request);
        }
        rules.requireOpen(scope);
        Instant now = Instant.now();
        request.setLeaderDecidedBy(leader.getId());
        request.setLeaderDecidedAt(now);
        request.setLeaderNote(GroupService.blankToNull(note));
        if (approve) {
            request.setStatus(Status.APPROVED);
            request.setDecidedBy(leader.getId());
            request.setDecidedAt(now);
            leaveRequests.saveAndFlush(request);
            removeMember(request, now);
        } else {
            request.setStatus(Status.REJECTED);
        }
        notifier.publish(DomainEvent.of(DomainEventType.LEAVE_DECIDED,
                "Leader đã " + (approve ? "đồng ý" : "từ chối") + " yêu cầu rời nhóm " + group.getName() + " của bạn"
                        + (approve ? "" : ". Bạn có thể gửi khiếu nại tới Moderator."),
                request.getUser().getId()).about(group.getId(), request.getId())
                .withDetails(request.getLeaderNote(), null).by(leader.getId()));
        return request;
    }

    /** A member whose request the Leader refused complains to the Moderator (6.4). */
    @Transactional
    public LeaveRequest escalate(UUID requestId, String reason, User user) {
        LeaveRequest snapshot = find(requestId);
        lockScopeShared(snapshot.getGroup().getScope().getId());
        gate.requireCanAct(snapshot.getEnrollment());
        LeaveRequest request = lock(requestId);
        if (!request.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("This is not your request");
        }
        if (request.getStatus() != Status.REJECTED || request.getLeaderDecidedBy() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_COMPLAIN",
                    "Only a request the Leader refused can be taken to the Moderator");
        }
        rules.requireNotFinalized(request.getGroup().getScope());
        if (!core.isMember(request.getGroup().getId(), user.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_MEMBER", "You are no longer in this group");
        }
        if (leaveRequests.findOpenByEnrollment(request.getEnrollment().getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "LEAVE_REQUEST_OPEN", "You already have a pending leave request");
        }
        request.setStatus(Status.PENDING_MODERATOR);
        request.setComplaint(true);
        if (reason != null && !reason.isBlank()) {
            request.setReason(GroupService.blankToNull(reason));
        }
        notifier.publish(DomainEvent.of(DomainEventType.COMPLAINT_RECEIVED,
                user.getFullName() + " (" + user.getStudentCode() + ") khiếu nại: Leader nhóm "
                        + request.getGroup().getCode() + " không cho rời nhóm",
                notifier.coordinators(request.getGroup().getScope().semesterCode()))
                .about(request.getGroup().getId(), request.getId()).withDetails(request.getReason(), null).by(user.getId()));
        return request;
    }

    @Transactional
    public LeaveRequest withdraw(UUID requestId, User user) {
        LeaveRequest snapshot = find(requestId);
        lockScopeShared(snapshot.getGroup().getScope().getId());
        gate.requireCanAct(snapshot.getEnrollment());
        rules.requireNotFinalized(snapshot.getGroup().getScope());
        LeaveRequest request = lock(requestId);
        if (!request.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("This is not your request");
        }
        if (!request.isOpen()) {
            throw closed(request);
        }
        request.setStatus(Status.WITHDRAWN);
        request.setDecidedAt(Instant.now());
        return request;
    }

    /** The Moderator decides a complaint or a request made after the deadline (7.1). */
    @Transactional
    public LeaveRequest moderatorDecide(UUID requestId, boolean approve, String note, User actor) {
        LeaveRequest snapshot = find(requestId);
        lockScopeShared(snapshot.getGroup().getScope().getId());
        access.requireModerator(snapshot.getGroup().getScope().semesterCode(), actor);
        core.lockEnrollment(snapshot.getEnrollment().getId());
        LeaveRequest request = lock(requestId);
        StudentGroup group = core.lock(request.getGroup().getId());
        if (request.getStatus() != Status.PENDING_MODERATOR) {
            throw closed(request);
        }
        Instant now = Instant.now();
        request.setStatus(approve ? Status.APPROVED : Status.REJECTED);
        request.setDecidedBy(actor.getId());
        request.setDecidedAt(now);
        request.setDecisionNote(GroupService.blankToNull(note));
        leaveRequests.saveAndFlush(request);
        if (approve) {
            removeMember(request, now);
        }
        audit.record("LeaveRequest", request.getId(), AuditAction.LEAVE_REQUEST_DECIDED, actor,
                group.getScope().semesterCode(), Map.of("approved", approve, "student",
                        String.valueOf(request.getUser().getStudentCode()), "group", group.getCode()));
        notifier.publish(DomainEvent.of(DomainEventType.LEAVE_DECIDED,
                "Moderator đã " + (approve ? "đồng ý" : "từ chối") + " yêu cầu rời nhóm " + group.getName() + " của bạn",
                java.util.stream.Stream.concat(java.util.stream.Stream.of(request.getUser().getId()),
                        java.util.Optional.ofNullable(core.leaderOf(group.getId())).stream().map(m -> m.getUser().getId()))
                        .distinct().toList()).about(group.getId(), request.getId())
                .withDetails(request.getDecisionNote(), null).by(actor.getId()));
        return request;
    }

    public List<LeaveRequestResponse> forGroup(UUID groupId, User viewer) {
        StudentGroup group = core.get(groupId);
        boolean staff = access.isModerator(group.getScope().semesterCode(), viewer);
        if (!staff && !core.isMember(groupId, viewer.getId())) {
            throw new AccessDeniedException("You are not a member of this group");
        }
        boolean leader = core.isLeader(groupId, viewer.getId());
        return leaveRequests.findByGroupIdOrderByCreatedAtDesc(groupId).stream()
                .filter(r -> staff || leader || r.getUser().getId().equals(viewer.getId()))
                .map(LeaveRequestResponse::from).toList();
    }

    public List<LeaveRequestResponse> mine(User viewer) {
        return leaveRequests.findByUserIdOrderByCreatedAtDesc(viewer.getId()).stream().map(LeaveRequestResponse::from).toList();
    }

    /** The Moderator's queue: complaints and requests after the deadline, by subject (and optionally scope). */
    public List<LeaveRequestResponse> waitingForModerator(UUID subjectId, UUID scopeId, String semesterCode, User viewer) {
        access.requireModerator(semesterCode, viewer);
        return leaveRequests.findWaitingForModerator(subjectId, scopeId).stream().map(LeaveRequestResponse::from).toList();
    }

    private void removeMember(LeaveRequest request, Instant now) {
        GroupMember member = core.membershipOf(request.getEnrollment().getId());
        if (member == null || !member.getGroup().getId().equals(request.getGroup().getId())) {
            return;
        }
        core.remove(member, GroupMember.LeftReason.LEAVE_APPROVED, NoGroupReason.LEFT_GROUP, now, true);
        // remove() closes the member's open requests; this one was decided, keep its outcome.
        request.setStatus(Status.APPROVED);
        notifier.publish(DomainEvent.of(DomainEventType.MEMBER_LEFT,
                request.getUser().getFullName() + " đã rời nhóm " + request.getGroup().getName(),
                core.activeMembers(request.getGroup().getId()).stream().map(m -> m.getUser().getId()).toList())
                .about(request.getGroup().getId(), null));
    }

    private LeaveRequest find(UUID id) {
        return leaveRequests.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Leave request", id));
    }

    private LeaveRequest lock(UUID id) {
        return locks.write(LeaveRequest.class, id);
    }

    private FormationScope lockScopeShared(UUID scopeId) {
        return locks.read(FormationScope.class, scopeId);
    }

    private static ApiException closed(LeaveRequest request) {
        return new ApiException(HttpStatus.CONFLICT, "REQUEST_CLOSED",
                "This leave request is " + request.getStatus().name().toLowerCase());
    }
}
