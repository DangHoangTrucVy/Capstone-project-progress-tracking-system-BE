package com.capstone.tracking.team;

import com.capstone.tracking.common.Locks;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.NoGroupReason;
import com.capstone.tracking.semester.FormationScope;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterPhase;
import com.capstone.tracking.semester.SemesterRepository;
import com.capstone.tracking.team.GroupMember.LeftReason;
import com.capstone.tracking.team.StudentGroup.FinalizeStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The membership rules every flow shares: joining (capacity from the semester's rules, one room per student), leaving,
 * the automatic Leader choice (6.7), disbanding a room back to empty (7.1) and issuing group codes (7.6). Callers hold
 * the semester (shared), the student's enrollment and the room's row locks, so concurrent operations cannot overfill a
 * room or put a student in two rooms (12); the database's partial unique indexes back both rules.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class GroupCore {

    private final StudentGroupRepository groups;
    private final GroupMemberRepository members;
    private final JoinRequestRepository requests;
    private final LeaveRequestRepository leaveRequests;
    private final LeaderTransferRepository transfers;
    private final SemesterRepository semesters;
    private final Notifier notifier;
    private final Locks locks;

    // ------------------------------------------------------------------ lookups

    @Transactional(propagation = Propagation.SUPPORTS)
    public StudentGroup get(UUID groupId) {
        StudentGroup group = groups.findById(groupId).orElseThrow(() -> ResourceNotFoundException.of("Room", groupId));
        return group;
    }

    public StudentGroup lock(UUID groupId) {
        StudentGroup group = locks.write(StudentGroup.class, groupId);
        if (group.isDissolved()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_CLOSED", "This room has been closed");
        }
        return group;
    }

    public Enrollment lockEnrollment(UUID enrollmentId) {
        return locks.write(Enrollment.class, enrollmentId);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public Semester semesterOf(StudentGroup group) {
        return semesters.findById(group.getScope().semesterCode()).orElseThrow();
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public List<GroupMember> activeMembers(UUID groupId) {
        return members.findActive(groupId);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public long size(UUID groupId) {
        return members.countActive(groupId);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public GroupMember leaderOf(UUID groupId) {
        return members.findActive(groupId).stream().filter(GroupMember::isLeader).findFirst().orElse(null);
    }

    /** The student's current room in the semester of this enrollment, or null. */
    @Transactional(propagation = Propagation.SUPPORTS)
    public StudentGroup groupOf(UUID enrollmentId) {
        return members.findActiveByEnrollment(enrollmentId).map(GroupMember::getGroup).orElse(null);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public GroupMember membershipOf(UUID enrollmentId) {
        return members.findActiveByEnrollment(enrollmentId).orElse(null);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean isLeader(UUID groupId, UUID userId) {
        return members.findActiveInGroup(groupId, userId).map(GroupMember::isLeader).orElse(false);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean isMember(UUID groupId, UUID userId) {
        return members.findActiveInGroup(groupId, userId).isPresent();
    }

    public GroupMember requireLeader(StudentGroup group, UUID userId) {
        GroupMember member = members.findActiveInGroup(group.getId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_LEADER", "You are not a member of this room"));
        if (!member.isLeader()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_LEADER", "Only the room's Leader may do this");
        }
        return member;
    }

    /** What everyone sees of the room (6.3). */
    @Transactional(propagation = Propagation.SUPPORTS)
    public RoomStatus status(StudentGroup group, long size, Semester semester, Instant now) {
        if (group.isDissolved()) {
            return RoomStatus.CLOSED;
        }
        if (group.isApproved()) {
            return RoomStatus.FINALIZED;
        }
        if (size == 0) {
            return RoomStatus.EMPTY;
        }
        if (group.isFinalizeRequested()) {
            return RoomStatus.FINALIZE_REQUESTED;
        }
        if (semester.getPhase() != SemesterPhase.OPENING && !semester.isBeforeDeadline(now)) {
            return RoomStatus.DEADLINE_LOCKED;
        }
        if (group.isLocked()) {
            return RoomStatus.LOCKED;
        }
        return size >= semester.getMaxGroupSize() ? RoomStatus.FULL : RoomStatus.OPEN;
    }

    // ------------------------------------------------------------------ joining

    /**
     * Adds the student to the room; the first student in an empty room is its Leader (6.2). Re-checks capacity and "no
     * room yet in this semester" under the locks the caller holds; every other open request and invite of the student
     * is then cancelled (6.2).
     */
    public GroupMember enroll(StudentGroup group, Enrollment enrollment, boolean leader, Semester semester, Instant now) {
        if (enrollment.isIneligible()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_ELIGIBLE", "The student is not eligible for Capstone");
        }
        if (!enrollment.getScope().getId().equals(group.getScope().getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "OTHER_MAJOR", "A room only takes students of its own major");
        }
        if (members.findActiveByEnrollment(enrollment.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_GROUP",
                    "The student already belongs to a room in this semester");
        }
        int max = semester.getMaxGroupSize();
        long size = members.countActive(group.getId());
        if (size >= max) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL", "The room is full (" + max + " members)");
        }
        GroupMember member = members.saveAndFlush(GroupMember.builder()
                .group(group)
                .enrollment(enrollment)
                .user(enrollment.getUser())
                .leader(leader || size == 0)
                .joinedAt(now)
                .build());
        enrollment.setNoGroupReason(null);
        requests.cancelPendingOfEnrollment(enrollment.getId(), JoinRequest.CancelReason.JOINED_ANOTHER_GROUP, now);
        if (members.countActive(group.getId()) >= max) {
            // A full room takes no more requests to join (6.2); invites are re-checked when answered.
            requests.cancelPendingOfGroup(group.getId(), JoinRequest.Type.APPLY, JoinRequest.CancelReason.ROOM_UNAVAILABLE, now);
        }
        return member;
    }

    // ------------------------------------------------------------------ leaving

    /**
     * Takes the member out of the room. With {@code settle}, a room left without a Leader gets one (6.7) and an empty
     * room goes back to empty; bulk removals pass false and call {@link #settle} once everyone is out (6.7: "loại hết
     * trước rồi mới chọn").
     */
    public void remove(GroupMember member, LeftReason reason, NoGroupReason noGroupReason, Instant now, boolean settle) {
        if (!member.isActive()) {
            return;
        }
        StudentGroup group = member.getGroup();
        member.setLeftAt(now);
        member.setLeftReason(reason);
        member.setLeader(false);
        members.saveAndFlush(member);
        Enrollment enrollment = member.getEnrollment();
        enrollment.setNoGroupReason(noGroupReason);
        leaveRequests.closeOpenOfEnrollment(enrollment.getId(), now);
        transfers.cancelPendingOfGroup(group.getId(), now);
        if (settle) {
            settle(group, now);
        }
    }

    /** Gives a room without a Leader the earliest-joined member as Leader (6.7), or resets it when empty. */
    public void settle(StudentGroup group, Instant now) {
        if (group.isDissolved()) {
            return;
        }
        List<GroupMember> active = members.findActive(group.getId());
        if (active.isEmpty()) {
            reset(group, now);
            return;
        }
        if (active.stream().anyMatch(GroupMember::isLeader)) {
            return;
        }
        // Oldest membership first; equal join times fall back to the student code, a fixed tie-breaker (12).
        GroupMember next = active.get(0);
        next.setLeader(true);
        members.saveAndFlush(next);
        notifier.publish(DomainEvent.of(DomainEventType.BECAME_LEADER,
                "Bạn đã tự động trở thành trưởng nhóm của " + label(group), next.getUser().getId())
                .about(group.getId(), null));
        notifier.publish(DomainEvent.of(DomainEventType.LEADER_CHANGED,
                next.getUser().getFullName() + " là trưởng nhóm mới của " + label(group),
                active.stream().map(m -> m.getUser().getId()).filter(id -> !id.equals(next.getUser().getId())).toList())
                .about(group.getId(), null));
    }

    /**
     * Disbands the room (7.1): every member leaves (becoming "chưa có nhóm"), its open requests, leave requests and
     * complaints are closed, the members are told, and the room goes back to empty; it is not deleted.
     */
    public int disband(StudentGroup group, UUID actorId, String why, Instant now) {
        List<GroupMember> active = members.findActive(group.getId());
        for (GroupMember m : active) {
            m.setLeftAt(now);
            m.setLeftReason(LeftReason.DISSOLVED);
            m.setLeader(false);
            m.getEnrollment().setNoGroupReason(NoGroupReason.GROUP_DISSOLVED);
        }
        members.saveAllAndFlush(active);
        reset(group, now);
        if (!active.isEmpty()) {
            notifier.publish(DomainEvent.of(DomainEventType.GROUP_DISSOLVED,
                    label(group) + " đã bị giải tán" + (why == null ? "" : " " + why)
                            + ". Bạn được chuyển vào danh sách chưa có nhóm.",
                    active.stream().map(m -> m.getUser().getId()).toList()).about(group.getId(), null).by(actorId));
        }
        return active.size();
    }

    /** An empty room: unlocked, no finalize request, no open request; the code (if any) stays, never reused. */
    private void reset(StudentGroup group, Instant now) {
        requests.cancelPendingOfGroup(group.getId(), JoinRequest.CancelReason.GROUP_DISSOLVED, now);
        leaveRequests.closeOpenOfGroup(group.getId(), now);
        transfers.cancelPendingOfGroup(group.getId(), now);
        group.setLocked(false);
        group.setFinalizeStatus(FinalizeStatus.NONE);
        group.setFinalizeRequestedAt(null);
        group.setFinalizeRequestedBy(null);
        group.setFinalized(false);
        group.setFinalizedAt(null);
        groups.saveAndFlush(group);
    }

    /** An empty room when formation ends is closed: it does not become a group (7.6). */
    public void close(StudentGroup group, Instant now) {
        group.setDissolvedAt(now);
        group.setDissolvedReason(StudentGroup.DissolveReason.CLOSED_EMPTY);
        groups.saveAndFlush(group);
    }

    // ------------------------------------------------------------------ becoming a group (7.6)

    /**
     * The room becomes a group: approved, "Đã chốt", with the next code of its major in the order groups are finalized,
     * e.g. SP26-SE-001. The scope row is locked while the number is taken, so two approvals never share a number.
     */
    public void finalizeRoom(StudentGroup group, UUID actorId, Instant now) {
        if (group.getCode() == null) {
            FormationScope scope = locks.write(FormationScope.class, group.getScope().getId());
            String prefix = scope.semesterCode() + "-" + scope.getMajor().getCode() + "-";
            String code;
            do {
                scope.setGroupSeq(scope.getGroupSeq() + 1);
                code = prefix + String.format("%03d", scope.getGroupSeq());
            } while (groups.existsByCode(code));
            if (code.length() > 40) {
                throw new ConflictException("Group code too long: " + code);
            }
            group.setCode(code);
        }
        group.setFinalizeStatus(FinalizeStatus.APPROVED);
        group.setFinalizeDecidedAt(now);
        group.setFinalizeDecidedBy(actorId);
        group.setFinalized(true);
        group.setFinalizedAt(now);
        group.setLocked(false);
        groups.saveAndFlush(group);
        requests.cancelPendingOfGroup(group.getId(), JoinRequest.Type.APPLY, JoinRequest.CancelReason.ROOM_UNAVAILABLE, now);
    }

    // ------------------------------------------------------------------ helpers

    public GroupMember saveMember(GroupMember member) {
        return members.saveAndFlush(member);
    }

    /** "Room 03" or "nhóm SP26-SE-001 (Room 03)" in messages. */
    public static String label(StudentGroup group) {
        return group.getCode() == null ? group.getName() : "nhóm " + group.getCode() + " (" + group.getName() + ")";
    }
}
