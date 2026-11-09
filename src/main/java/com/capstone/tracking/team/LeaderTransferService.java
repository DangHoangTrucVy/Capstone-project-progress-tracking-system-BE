package com.capstone.tracking.team;

import com.capstone.tracking.common.Locks;
import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.NoGroupReason;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterGuard;
import com.capstone.tracking.semester.SemesterPhase;
import com.capstone.tracking.team.LeaderTransfer.Status;
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
 * The Leader hands the role to another member, who must accept (6.4). With {@code leaveAfter} the old Leader leaves
 * in the same step (only before the deadline, when leaving is allowed). After the list is finalized the transfer is
 * still allowed: it is recorded, the Moderator is told, and the Excel export shows it as the last change.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LeaderTransferService {

    private final LeaderTransferRepository transfers;
    private final GroupCore core;
    private final SemesterGuard guard;
    private final Notifier notifier;
    private final AuditService audit;
    private final Locks locks;
    private final com.capstone.tracking.roster.StudentGate gate;

    public record TransferResponse(UUID id, UUID groupId, String groupCode, String groupName, UUID fromUserId,
                                   String fromName, UUID toUserId, String toName, boolean leaveAfter,
                                   boolean afterFinalize, Status status, Instant createdAt, Instant respondedAt) {
        public static TransferResponse from(LeaderTransfer t) {
            return new TransferResponse(t.getId(), t.getGroup().getId(), t.getGroup().getCode(), t.getGroup().getName(),
                    t.getFromUser().getId(), t.getFromUser().getFullName(), t.getToUser().getId(),
                    t.getToUser().getFullName(), t.isLeaveAfter(), t.isAfterFinalize(), t.getStatus(), t.getCreatedAt(),
                    t.getRespondedAt());
        }
    }

    @Transactional
    public LeaderTransfer propose(UUID groupId, UUID toUserId, boolean leaveAfter, User leader) {
        StudentGroup group = core.get(groupId);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        gate.requireCanAct(semester.getCode(), leader);
        group = core.lock(groupId);
        GroupMember from = core.requireLeader(group, leader.getId());
        if (semester.getPhase() == SemesterPhase.OPENING) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_STARTED", "Group formation has not started yet");
        }
        guard.requireNotClosed(semester);
        boolean afterFinalize = group.isApproved() || semester.getRound1FinalizedAt() != null;
        if (leaveAfter) {
            guard.requireBeforeDeadline(semester); // nobody leaves by themselves after the deadline
            GroupService.requireNotApproved(group);
        }
        if (toUserId == null || toUserId.equals(leader.getId())) {
            throw new BadRequestException("Choose another member of the group");
        }
        GroupMember to = core.activeMembers(groupId).stream().filter(m -> m.getUser().getId().equals(toUserId))
                .findFirst().orElseThrow(() -> new BadRequestException("The new Leader must be a member of the group"));
        if (transfers.findPending(groupId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_PENDING", "A Leader transfer is already waiting for an answer");
        }
        LeaderTransfer transfer = transfers.saveAndFlush(LeaderTransfer.builder()
                .group(group).fromUser(from.getUser()).toUser(to.getUser())
                .leaveAfter(leaveAfter).afterFinalize(afterFinalize).build());
        notifier.publish(DomainEvent.of(DomainEventType.LEADER_TRANSFER_REQUESTED,
                leader.getFullName() + " muốn chuyển chức trưởng nhóm " + GroupCore.label(group) + " cho bạn", toUserId)
                .about(groupId, transfer.getId()).by(leader.getId()));
        return transfer;
    }

    @Transactional
    public LeaderTransfer answer(UUID transferId, boolean accept, User user) {
        LeaderTransfer snapshot = find(transferId);
        Semester semester = locks.readSemester(snapshot.getGroup().getScope().semesterCode());
        gate.requireCanAct(semester.getCode(), user);
        guard.requireNotClosed(semester);
        GroupMember fromSnapshot = core.activeMembers(snapshot.getGroup().getId()).stream()
                .filter(m -> m.getUser().getId().equals(snapshot.getFromUser().getId())).findFirst().orElse(null);
        if (accept && snapshot.isLeaveAfter() && fromSnapshot != null) {
            core.lockEnrollment(fromSnapshot.getEnrollment().getId());
        }
        LeaderTransfer transfer = locks.write(LeaderTransfer.class, transferId);
        StudentGroup group = core.lock(transfer.getGroup().getId());
        if (!transfer.getToUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("This transfer is not addressed to you");
        }
        if (transfer.getStatus() != Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_CLOSED", "This transfer is " + transfer.getStatus());
        }
        Instant now = Instant.now();
        transfer.setRespondedAt(now);
        if (!accept) {
            transfer.setStatus(Status.DECLINED);
            notifier.publish(DomainEvent.of(DomainEventType.LEADER_TRANSFER_ANSWERED,
                    user.getFullName() + " đã từ chối nhận chức trưởng nhóm " + GroupCore.label(group),
                    transfer.getFromUser().getId()).about(group.getId(), transfer.getId()).by(user.getId()));
            return transfer;
        }
        List<GroupMember> active = core.activeMembers(group.getId());
        GroupMember from = active.stream().filter(m -> m.getUser().getId().equals(transfer.getFromUser().getId())
                && m.isLeader()).findFirst().orElse(null);
        GroupMember to = active.stream().filter(m -> m.getUser().getId().equals(user.getId())).findFirst().orElse(null);
        if (from == null || to == null) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_STALE", "The group changed; this transfer no longer applies");
        }
        if (transfer.isLeaveAfter()) {
            guard.requireBeforeDeadline(semester);
        }
        // One Leader per group is a unique index: clear the old one first.
        from.setLeader(false);
        core.saveMember(from);
        to.setLeader(true);
        core.saveMember(to);
        transfer.setStatus(Status.ACCEPTED);
        if (transfer.isLeaveAfter()) {
            core.remove(from, GroupMember.LeftReason.LEFT, NoGroupReason.LEFT_GROUP, now, true);
        }
        if (group.isApproved() || semester.getRound1FinalizedAt() != null) {
            // 6.6: still allowed after finalization; the Moderator is told.
            String change = "Chuyển trưởng " + GroupCore.label(group) + " từ " + from.getUser().getFullName() + " sang "
                    + user.getFullName() + " lúc " + VnTime.format(now);
            audit.record("StudentGroup", group.getId(), AuditAction.LEADER_TRANSFERRED_AFTER_FINALIZE, user,
                    semester.getCode(), Map.of("from", from.getUser().getId().toString(), "to", user.getId().toString()));
            notifier.publish(DomainEvent.of(DomainEventType.LEADER_CHANGED_AFTER_FINALIZE, change,
                    notifier.coordinators(semester.getCode())).about(group.getId(), transfer.getId()).by(user.getId()));
        }
        notifier.publish(DomainEvent.of(DomainEventType.LEADER_CHANGED,
                user.getFullName() + " là trưởng nhóm mới của " + GroupCore.label(group),
                core.activeMembers(group.getId()).stream().map(m -> m.getUser().getId()).toList())
                .about(group.getId(), transfer.getId()).by(user.getId()));
        if (transfer.isLeaveAfter()) {
            notifier.publish(DomainEvent.of(DomainEventType.LEADER_TRANSFER_ANSWERED,
                    user.getFullName() + " đã nhận chức trưởng nhóm; bạn đã rời " + GroupCore.label(group),
                    transfer.getFromUser().getId()).about(group.getId(), transfer.getId()).by(user.getId()));
        }
        return transfer;
    }

    @Transactional
    public LeaderTransfer cancel(UUID transferId, User user) {
        LeaderTransfer snapshot = find(transferId);
        Semester semester = locks.readSemester(snapshot.getGroup().getScope().semesterCode());
        gate.requireCanAct(semester.getCode(), user);
        LeaderTransfer transfer = locks.write(LeaderTransfer.class, transferId);
        if (!transfer.getFromUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("Only the Leader who proposed it can cancel this transfer");
        }
        if (transfer.getStatus() != Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_CLOSED", "This transfer is " + transfer.getStatus());
        }
        transfer.setStatus(Status.CANCELLED);
        transfer.setRespondedAt(Instant.now());
        return transfer;
    }

    public List<TransferResponse> mine(User user) {
        return transfers.findInvolving(user.getId()).stream().map(TransferResponse::from).toList();
    }

    private LeaderTransfer find(UUID id) {
        return transfers.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Transfer", id));
    }
}
