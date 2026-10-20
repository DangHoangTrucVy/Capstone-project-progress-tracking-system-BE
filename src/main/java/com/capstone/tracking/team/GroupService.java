package com.capstone.tracking.team;

import com.capstone.tracking.common.Locks;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.NoGroupReason;
import com.capstone.tracking.roster.StudentGate;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.FormationScopeRepository;
import com.capstone.tracking.subject.ScopePhase;
import com.capstone.tracking.subject.ScopeRules;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The student side of groups (6.1, 6.3, 6.4): creating one, the Leader's settings, lock, kick, and leaving.
 * Lock order everywhere: scope (shared), the student's enrollment, then the group.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupService {

    private final StudentGroupRepository groups;
    private final FormationScopeRepository scopes;
    private final LeaveRequestRepository leaveRequests;
    private final GroupCore core;
    private final StudentGate gate;
    private final ScopeRules rules;
    private final Notifier notifier;
    private final Locks locks;

    public record GroupRequest(String name, String wantedSkills) {}

    /** The outcome of "leave": either the student left at once, or a request went to the Leader or the Moderator. */
    public record LeaveOutcome(boolean left, LeaveRequest request) {}

    /** A student without a group in the subject creates one and becomes its Leader and first member (6.1). */
    @Transactional
    public StudentGroup create(UUID subjectId, GroupRequest request, User user) {
        Enrollment enrollment = gate.requireCanAct(subjectId, user);
        FormationScope scope = locks.write(FormationScope.class, enrollment.getScope().getId());
        rules.requireOpen(scope);
        enrollment = core.lockEnrollment(enrollment.getId());
        String name = name(request);
        Instant now = Instant.now();
        StudentGroup group = groups.saveAndFlush(StudentGroup.builder()
                .scope(scope)
                .code(core.nextCode(scope))
                .name(name)
                .wantedSkills(skills(request))
                .createdBy(user.getId())
                .build());
        core.enroll(group, enrollment, true, now);
        return group;
    }

    public StudentGroup get(UUID groupId) {
        return core.get(groupId);
    }

    /** The Leader renames the group or changes the skills it is looking for; not once the list is finalized. */
    @Transactional
    public StudentGroup update(UUID groupId, GroupRequest request, User user) {
        StudentGroup group = core.get(groupId);
        lockScopeShared(group.getScope().getId());
        gate.requireCanAct(group.getScope().getSubject().getId(), user);
        group = core.lock(groupId);
        core.requireLeader(group, user.getId());
        rules.requireNotFinalized(group.getScope());
        if (request.name() != null) {
            group.setName(name(request));
        }
        group.setWantedSkills(skills(request));
        return group;
    }

    /** The Leader locks or unlocks the group, before the deadline only (6.3). */
    @Transactional
    public StudentGroup setLocked(UUID groupId, boolean locked, User user) {
        StudentGroup group = core.get(groupId);
        lockScopeShared(group.getScope().getId());
        gate.requireCanAct(group.getScope().getSubject().getId(), user);
        group = core.lock(groupId);
        core.requireLeader(group, user.getId());
        rules.requireOpen(group.getScope());
        group.setLocked(locked);
        return group;
    }

    /** The Leader removes a member, before the deadline only (2, 6.3). */
    @Transactional
    public void kick(UUID groupId, UUID memberUserId, User user) {
        StudentGroup group = core.get(groupId);
        lockScopeShared(group.getScope().getId());
        gate.requireCanAct(group.getScope().getSubject().getId(), user);
        GroupMember target = core.activeMembers(groupId).stream()
                .filter(m -> m.getUser().getId().equals(memberUserId)).findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("Member", memberUserId));
        core.lockEnrollment(target.getEnrollment().getId());
        group = core.lock(groupId);
        core.requireLeader(group, user.getId());
        rules.requireOpen(group.getScope());
        if (target.getUser().getId().equals(user.getId())) {
            throw new BadRequestException("A Leader cannot remove themselves; transfer the role or leave the group");
        }
        Instant now = Instant.now();
        core.remove(target, GroupMember.LeftReason.KICKED, null, now, true);
        notifier.publish(DomainEvent.of(DomainEventType.MEMBER_KICKED,
                "Bạn đã bị Leader mời ra khỏi nhóm " + group.getName(), memberUserId)
                .about(group.getId(), null).by(user.getId()));
    }

    /**
     * Leaving (6.4). Before the deadline a member of an unlocked group leaves at once; the Leader too, and the system
     * picks the next Leader (6.5); the last member leaving dissolves the group. In a locked group a member asks the
     * Leader (the Leader must transfer the role or unlock first). After the deadline nobody leaves by themselves: the
     * request goes to the Moderator.
     */
    @Transactional
    public LeaveOutcome leave(UUID groupId, String reason, User user) {
        StudentGroup group = core.get(groupId);
        FormationScope scope = lockScopeShared(group.getScope().getId());
        GroupMember member = core.activeMembers(groupId).stream()
                .filter(m -> m.getUser().getId().equals(user.getId())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_MEMBER", "You are not a member of this group"));
        Enrollment enrollment = core.lockEnrollment(member.getEnrollment().getId());
        gate.requireCanAct(enrollment);
        group = core.lock(groupId);
        Instant now = Instant.now();
        if (rules.isOpen(scope, now)) {
            if (!group.isLocked()) {
                core.remove(member, GroupMember.LeftReason.LEFT, NoGroupReason.LEFT_GROUP, now, true);
                notifier.publish(DomainEvent.of(DomainEventType.MEMBER_LEFT,
                        user.getFullName() + " đã rời nhóm " + group.getName(),
                        core.activeMembers(groupId).stream().map(m -> m.getUser().getId()).toList())
                        .about(group.getId(), null).by(user.getId()));
                return new LeaveOutcome(true, null);
            }
            if (member.isLeader()) {
                throw new ApiException(HttpStatus.CONFLICT, "LEADER_MUST_TRANSFER",
                        "The group is locked: transfer the Leader role (and leave) or unlock the group first");
            }
            return new LeaveOutcome(false, openRequest(group, enrollment, member, reason, LeaveRequest.Status.PENDING_LEADER));
        }
        if (scope.getPhase() == ScopePhase.LOCKED || scope.getPhase() == ScopePhase.OPEN) {
            // Past the deadline (OPEN only until the scheduler marks it LOCKED): the Moderator decides.
            return new LeaveOutcome(false, openRequest(group, enrollment, member, reason, LeaveRequest.Status.PENDING_MODERATOR));
        }
        rules.requireOpen(scope); // PREPARING or FINALIZED: explains why nothing can be done
        return new LeaveOutcome(false, null);
    }

    private LeaveRequest openRequest(StudentGroup group, Enrollment enrollment, GroupMember member, String reason,
                                     LeaveRequest.Status status) {
        if (leaveRequests.findOpenByEnrollment(enrollment.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "LEAVE_REQUEST_OPEN", "You already have a pending leave request");
        }
        LeaveRequest request = leaveRequests.saveAndFlush(LeaveRequest.builder()
                .group(group)
                .enrollment(enrollment)
                .user(member.getUser())
                .status(status)
                .reason(blankToNull(reason))
                .build());
        String who = member.getUser().getFullName();
        if (status == LeaveRequest.Status.PENDING_LEADER) {
            GroupMember leader = core.leaderOf(group.getId());
            notifier.publish(DomainEvent.of(DomainEventType.LEAVE_REQUESTED, who + " xin rời nhóm " + group.getName(),
                    leader == null ? null : leader.getUser().getId()).about(group.getId(), request.getId())
                    .withDetails(request.getReason(), null).by(member.getUser().getId()));
        } else {
            notifier.publish(DomainEvent.of(DomainEventType.COMPLAINT_RECEIVED,
                    who + " (" + member.getUser().getStudentCode() + ") xin rời nhóm " + group.getCode() + " sau deadline",
                    notifier.coordinators(group.getScope().semesterCode())).about(group.getId(), request.getId())
                    .withDetails(request.getReason(), null).by(member.getUser().getId()));
        }
        return request;
    }

    private FormationScope lockScopeShared(UUID scopeId) {
        return locks.read(FormationScope.class, scopeId);
    }

    private static String name(GroupRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("The group name is required");
        }
        if (request.name().trim().length() > 100) {
            throw new BadRequestException("The group name is at most 100 characters");
        }
        return request.name().trim();
    }

    private static String skills(GroupRequest request) {
        String skills = blankToNull(request.wantedSkills());
        if (skills != null && skills.length() > 500) {
            throw new BadRequestException("The wanted skills are at most 500 characters");
        }
        return skills;
    }

    static String blankToNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim().length() > 1000 ? text.trim().substring(0, 1000) : text.trim();
    }

    public List<StudentGroup> activeInScope(UUID scopeId) {
        return groups.findActiveByScope(scopeId);
    }
}
