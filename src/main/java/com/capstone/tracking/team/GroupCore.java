package com.capstone.tracking.team;

import com.capstone.tracking.common.Locks;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.NoGroupReason;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.ScopePhase;
import com.capstone.tracking.subject.ScopeRules;
import com.capstone.tracking.team.GroupMember.LeftReason;
import com.capstone.tracking.team.StudentGroup.DissolveReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The membership rules every flow shares: joining (capacity, one group per subject), leaving, the automatic Leader
 * choice (6.5) and dissolving. Callers hold the group's row lock (and the student's enrollment lock when a student
 * joins or leaves), so concurrent operations cannot overfill a group or put a student in two groups (12); the
 * database's partial unique indexes back both rules.
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
    private final EnrollmentRepository enrollments;
    private final ScopeRules rules;
    private final Notifier notifier;
    private final Locks locks;

    // ------------------------------------------------------------------ lookups

    @Transactional(propagation = Propagation.SUPPORTS)
    public StudentGroup get(UUID groupId) {
        return groups.findById(groupId).orElseThrow(() -> ResourceNotFoundException.of("Group", groupId));
    }

    public StudentGroup lock(UUID groupId) {
        StudentGroup group = locks.write(StudentGroup.class, groupId);
        if (group.isDissolved()) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_DISSOLVED", "This group has been dissolved");
        }
        return group;
    }

    public Enrollment lockEnrollment(UUID enrollmentId) {
        return locks.write(Enrollment.class, enrollmentId);
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

    /** The student's current group in the subject of this enrollment, or null. */
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
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_LEADER", "You are not a member of this group"));
        if (!member.isLeader()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_LEADER", "Only the group's Leader may do this");
        }
        return member;
    }

    /** What students see of the group (6.3). */
    @Transactional(propagation = Propagation.SUPPORTS)
    public GroupState state(StudentGroup group, long size, Instant now) {
        if (group.isDissolved()) {
            return GroupState.DISSOLVED;
        }
        boolean beforeDeadline = rules.isOpen(group.getScope(), now)
                || group.getScope().getPhase() == ScopePhase.PREPARING;
        if (group.isLocked() || !beforeDeadline) {
            return GroupState.LOCKED;
        }
        return size >= group.getScope().getSubject().getMaxGroupSize() ? GroupState.FULL : GroupState.OPEN;
    }

    // ------------------------------------------------------------------ joining

    /**
     * Adds the student to the group. Re-checks capacity and "no group in this subject" under the locks the caller
     * holds; every other open Apply/Invite of the student in this subject is then cancelled (6.2).
     */
    public GroupMember enroll(StudentGroup group, Enrollment enrollment, boolean leader, Instant now) {
        if (!enrollment.getScope().getId().equals(group.getScope().getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "OTHER_MAJOR",
                    "A group only takes students of its own major and subject");
        }
        if (members.findActiveByEnrollment(enrollment.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_GROUP",
                    "The student already belongs to a group in this subject");
        }
        int max = group.getScope().getSubject().getMaxGroupSize();
        if (members.countActive(group.getId()) >= max) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL", "The group is full (" + max + " members)");
        }
        GroupMember member = members.saveAndFlush(GroupMember.builder()
                .group(group)
                .enrollment(enrollment)
                .user(enrollment.getUser())
                .leader(leader)
                .joinedAt(now)
                .build());
        enrollment.setNoGroupReason(null);
        requests.cancelPendingOfEnrollment(enrollment.getId(), JoinRequest.CancelReason.JOINED_ANOTHER_GROUP, now);
        return member;
    }

    // ------------------------------------------------------------------ leaving

    /**
     * Takes the member out of the group. With {@code settle}, a group left without a Leader gets one (6.5) and an empty
     * group is dissolved; bulk removals pass false and call {@link #settle} once everyone is out (6.5: "loại hết trước
     * rồi mới chọn Leader").
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

    /** Gives a group without a Leader the earliest-joined member as Leader (6.5), or dissolves it when empty. */
    public void settle(StudentGroup group, Instant now) {
        if (group.isDissolved()) {
            return;
        }
        List<GroupMember> active = members.findActive(group.getId());
        if (active.isEmpty()) {
            dissolve(group, DissolveReason.EMPTY, null, now);
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
                "Bạn đã tự động trở thành Leader của nhóm " + group.getName() + " (" + group.getCode() + ")",
                next.getUser().getId()).about(group.getId(), null));
        notifier.publish(DomainEvent.of(DomainEventType.LEADER_CHANGED,
                next.getUser().getFullName() + " là Leader mới của nhóm " + group.getName(),
                active.stream().map(m -> m.getUser().getId()).filter(id -> !id.equals(next.getUser().getId())).toList())
                .about(group.getId(), null));
    }

    /**
     * Dissolves the group: every member leaves (becoming "chưa có nhóm"), its open requests, leave requests and
     * complaints are closed (7.1), and the members are told.
     */
    public void dissolve(StudentGroup group, DissolveReason reason, UUID actorId, Instant now) {
        if (group.isDissolved()) {
            return;
        }
        List<GroupMember> active = members.findActive(group.getId());
        for (GroupMember m : active) {
            m.setLeftAt(now);
            m.setLeftReason(LeftReason.DISSOLVED);
            m.setLeader(false);
            m.getEnrollment().setNoGroupReason(NoGroupReason.GROUP_DISSOLVED);
        }
        members.saveAllAndFlush(active);
        requests.cancelPendingOfGroup(group.getId(), JoinRequest.CancelReason.GROUP_DISSOLVED, now);
        leaveRequests.closeOpenOfGroup(group.getId(), now);
        transfers.cancelPendingOfGroup(group.getId(), now);
        group.setDissolvedAt(now);
        group.setDissolvedReason(reason);
        groups.saveAndFlush(group);
        if (!active.isEmpty()) {
            String why = switch (reason) {
                case TOO_SMALL_AT_DEADLINE -> " vì có dưới " + group.getScope().getSubject().getMinGroupSize()
                        + " thành viên khi tới deadline";
                case MODERATOR -> " bởi Moderator";
                case EMPTY -> "";
            };
            notifier.publish(DomainEvent.of(DomainEventType.GROUP_DISSOLVED,
                    "Nhóm " + group.getName() + " (" + group.getCode() + ") đã bị giải tán" + why
                            + ". Bạn được chuyển vào danh sách chưa có nhóm.",
                    active.stream().map(m -> m.getUser().getId()).toList()).about(group.getId(), null).by(actorId));
        }
    }

    // ------------------------------------------------------------------ helpers

    public GroupMember saveMember(GroupMember member) {
        return members.saveAndFlush(member);
    }

    /** A short, unique group code: <semester>-<subject>-<major>-<n>. */
    public String nextCode(FormationScope scope) {
        String prefix = scope.semesterCode() + "-" + scope.getSubject().getCode() + "-" + scope.getMajor().getCode() + "-";
        long n = groups.countAllInScope(scope.getId()) + 1;
        String code;
        do {
            code = prefix + String.format("%03d", n++);
        } while (groups.existsByCode(code));
        if (code.length() > 40) {
            throw new ConflictException("Group code too long: " + code);
        }
        return code;
    }
}
