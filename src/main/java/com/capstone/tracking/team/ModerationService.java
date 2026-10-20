package com.capstone.tracking.team;

import com.capstone.tracking.common.Locks;
import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.NoGroupReason;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.FormationScopeRepository;
import com.capstone.tracking.subject.ScopeRules;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Moderator's coordination until finalization (7.1): put students without a group into groups below the maximum
 * size (locked groups too), move students between groups, take a student out, create a group and appoint its Leader
 * without Apply or Invite, and dissolve groups, including Leader-locked groups before the deadline; every change is
 * recorded and the people affected are told.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModerationService {

    private final StudentGroupRepository groups;
    private final EnrollmentRepository enrollments;
    private final FormationScopeRepository scopes;
    private final GroupCore core;
    private final ScopeRules rules;
    private final Access access;
    private final Notifier notifier;
    private final AuditService audit;
    private final Locks locks;
    private final LeaderTransferRepository transfers;

    public record CreateGroupRequest(String name, UUID leaderUserId, List<UUID> memberUserIds) {}

    /** A student without a group joins a group below the maximum size. */
    @Transactional
    public GroupMember addMember(UUID groupId, UUID studentUserId, User actor) {
        StudentGroup group = core.get(groupId);
        FormationScope scope = requireCoordinating(group.getScope(), actor);
        Enrollment enrollment = core.lockEnrollment(enrollmentOf(scope, studentUserId).getId());
        requireGroupable(enrollment);
        group = core.lock(groupId);
        Instant now = Instant.now();
        GroupMember member = core.enroll(group, enrollment, false, now);
        audit.record("StudentGroup", group.getId(), AuditAction.MODERATOR_ADDED_MEMBER, actor, scope.semesterCode(),
                Map.of("student", code(enrollment), "group", group.getCode()));
        notifier.publish(DomainEvent.of(DomainEventType.ADDED_BY_MODERATOR,
                "Moderator đã xếp bạn vào nhóm " + group.getName() + " (" + group.getCode() + ")",
                enrollment.getUser().getId()).about(group.getId(), null).by(actor.getId()));
        notifyMembers(group, enrollment.getUser().getFullName() + " được Moderator xếp vào nhóm " + group.getName(),
                actor, enrollment.getUser().getId());
        return member;
    }

    /**
     * Moves a student from their group to another of the same scope. A moved Leader is replaced in the old group
     * (6.5); an old group left empty is dissolved. A group that falls below the minimum is not dissolved here: the
     * Moderator decides (7.1).
     */
    @Transactional
    public GroupMember move(UUID toGroupId, UUID studentUserId, User actor) {
        StudentGroup target = core.get(toGroupId);
        FormationScope scope = requireCoordinating(target.getScope(), actor);
        Enrollment enrollment = core.lockEnrollment(enrollmentOf(scope, studentUserId).getId());
        GroupMember current = core.membershipOf(enrollment.getId());
        if (current == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_GROUP", "The student has no group; add them instead");
        }
        if (current.getGroup().getId().equals(toGroupId)) {
            throw new BadRequestException("The student is already in this group");
        }
        // Both groups are locked in id order, so two opposite moves cannot deadlock.
        List<UUID> ids = new ArrayList<>(List.of(current.getGroup().getId(), toGroupId));
        ids.sort(Comparator.naturalOrder());
        Map<UUID, StudentGroup> locked = new java.util.HashMap<>();
        for (UUID id : ids) {
            locked.put(id, core.lock(id));
        }
        StudentGroup from = locked.get(current.getGroup().getId());
        StudentGroup to = locked.get(toGroupId);
        int max = scope.getSubject().getMaxGroupSize();
        if (core.size(to.getId()) >= max) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL", "The group is full (" + max + " members)");
        }
        Instant now = Instant.now();
        boolean wasLeader = current.isLeader();
        core.remove(current, GroupMember.LeftReason.MOVED, null, now, true);
        GroupMember member = core.enroll(to, enrollment, false, now);
        audit.record("StudentGroup", to.getId(), AuditAction.MODERATOR_MOVED_MEMBER, actor, scope.semesterCode(),
                Map.of("student", code(enrollment), "from", from.getCode(), "to", to.getCode(), "wasLeader", wasLeader));
        notifier.publish(DomainEvent.of(DomainEventType.MOVED_BY_MODERATOR,
                "Moderator đã chuyển bạn từ nhóm " + from.getName() + " sang nhóm " + to.getName(),
                enrollment.getUser().getId()).about(to.getId(), null).by(actor.getId()));
        notifyMembers(from, enrollment.getUser().getFullName() + " được Moderator chuyển sang nhóm khác", actor, null);
        notifyMembers(to, enrollment.getUser().getFullName() + " được Moderator xếp vào nhóm " + to.getName(), actor,
                enrollment.getUser().getId());
        return member;
    }

    /** Takes a student out of their group (not eligible any more, or they asked to leave). */
    @Transactional
    public void removeMember(UUID groupId, UUID studentUserId, String reason, User actor) {
        StudentGroup group = core.get(groupId);
        FormationScope scope = requireCoordinating(group.getScope(), actor);
        Enrollment enrollment = core.lockEnrollment(enrollmentOf(scope, studentUserId).getId());
        group = core.lock(groupId);
        GroupMember member = core.membershipOf(enrollment.getId());
        if (member == null || !member.getGroup().getId().equals(groupId)) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_MEMBER", "The student is not in this group");
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required");
        }
        Instant now = Instant.now();
        core.remove(member, GroupMember.LeftReason.REMOVED_BY_MODERATOR, NoGroupReason.REMOVED_BY_MODERATOR, now, true);
        audit.record("StudentGroup", group.getId(), AuditAction.MODERATOR_REMOVED_MEMBER, actor, scope.semesterCode(),
                Map.of("student", code(enrollment), "group", group.getCode(), "reason", reason.trim()));
        notifier.publish(DomainEvent.of(DomainEventType.REMOVED_BY_MODERATOR,
                "Moderator đã đưa bạn ra khỏi nhóm " + group.getName(), enrollment.getUser().getId())
                .about(group.getId(), null).withDetails(reason.trim(), null).by(actor.getId()));
        notifyMembers(group, enrollment.getUser().getFullName() + " đã được Moderator đưa ra khỏi nhóm", actor, null);
    }

    /** A new group of students without one, with the Leader the Moderator appoints (7.1, ĐỀ XUẤT). */
    @Transactional
    public StudentGroup createGroup(UUID scopeId, CreateGroupRequest request, User actor) {
        FormationScope scope = scopes.findById(scopeId).orElseThrow(() -> ResourceNotFoundException.of("Scope", scopeId));
        access.requireModerator(scope.semesterCode(), actor);
        scope = locks.write(FormationScope.class, scopeId);
        requireCoordinating(scope, actor);
        if (request.name() == null || request.name().isBlank() || request.name().trim().length() > 100) {
            throw new BadRequestException("The group name is required (at most 100 characters)");
        }
        if (request.leaderUserId() == null) {
            throw new BadRequestException("Choose the group's Leader");
        }
        Set<UUID> people = new LinkedHashSet<>();
        people.add(request.leaderUserId());
        if (request.memberUserIds() != null) {
            people.addAll(request.memberUserIds());
        }
        int max = scope.getSubject().getMaxGroupSize();
        if (people.size() > max) {
            throw new BadRequestException("A group has at most " + max + " members");
        }
        List<Enrollment> locked = new ArrayList<>();
        for (UUID id : people.stream().sorted().toList()) {
            Enrollment e = core.lockEnrollment(enrollmentOf(scope, id).getId());
            requireGroupable(e);
            locked.add(e);
        }
        Instant now = Instant.now();
        StudentGroup group = groups.saveAndFlush(StudentGroup.builder()
                .scope(scope).code(core.nextCode(scope)).name(request.name().trim()).createdBy(actor.getId()).build());
        Enrollment leader = locked.stream().filter(e -> e.getUser().getId().equals(request.leaderUserId())).findFirst().orElseThrow();
        core.enroll(group, leader, true, now);
        for (Enrollment e : locked) {
            if (e != leader) {
                core.enroll(group, e, false, now);
            }
        }
        audit.record("StudentGroup", group.getId(), AuditAction.MODERATOR_CREATED_GROUP, actor, scope.semesterCode(),
                Map.of("group", group.getCode(), "leader", code(leader), "members", locked.stream().map(ModerationService::code).toList()));
        notifier.publish(DomainEvent.of(DomainEventType.ADDED_BY_MODERATOR,
                "Moderator đã lập nhóm " + group.getName() + " (" + group.getCode() + ") và xếp bạn vào nhóm",
                locked.stream().map(e -> e.getUser().getId()).toList()).about(group.getId(), null).by(actor.getId()));
        notifier.publish(DomainEvent.of(DomainEventType.BECAME_LEADER,
                "Moderator đã chỉ định bạn làm Leader nhóm " + group.getName(), leader.getUser().getId())
                .about(group.getId(), null).by(actor.getId()));
        return group;
    }

    /** Moderator appoints a current member directly, without the student transfer consent flow (7.1). */
    @Transactional
    public StudentGroup appointLeader(UUID groupId, UUID userId, User actor) {
        StudentGroup group = core.get(groupId);
        FormationScope scope = requireCoordinating(group.getScope(), actor);
        group = core.lock(groupId);
        List<GroupMember> active = core.activeMembers(groupId);
        GroupMember next = active.stream().filter(m -> m.getUser().getId().equals(userId)).findFirst()
                .orElseThrow(() -> new BadRequestException("The Leader must be a current member of this group"));
        if (next.isLeader()) {
            return group;
        }
        active.stream().filter(GroupMember::isLeader).forEach(m -> {
            m.setLeader(false);
            core.saveMember(m);
        });
        next.setLeader(true);
        core.saveMember(next);
        transfers.cancelPendingOfGroup(groupId, Instant.now());
        audit.record("StudentGroup", groupId, AuditAction.MODERATOR_APPOINTED_LEADER, actor, scope.semesterCode(),
                Map.of("leader", userId.toString()));
        notifier.publish(DomainEvent.of(DomainEventType.LEADER_CHANGED,
                "Moderator đã chỉ định " + next.getUser().getFullName() + " làm Leader nhóm " + group.getName(),
                active.stream().map(m -> m.getUser().getId()).toList()).about(groupId, null).by(actor.getId()));
        return group;
    }

    /** Atomic merge: lock every enrollment first, then both groups in stable order, check capacity again. */
    @Transactional
    public StudentGroup merge(UUID targetId, UUID sourceId, User actor) {
        if (sourceId == null || targetId.equals(sourceId)) {
            throw new BadRequestException("Choose a different source group");
        }
        StudentGroup target = core.get(targetId);
        FormationScope scope = requireCoordinating(target.getScope(), actor);
        StudentGroup source = core.get(sourceId);
        if (!source.getScope().getId().equals(scope.getId())) {
            throw new BadRequestException("Groups must belong to the same semester and major");
        }
        List<GroupMember> moving = core.activeMembers(sourceId);
        moving.stream().map(m -> m.getEnrollment().getId()).sorted().forEach(core::lockEnrollment);
        List.of(targetId, sourceId).stream().sorted().forEach(core::lock);
        // Re-read under locks: concurrent leaves/joins may have changed the source since the first read.
        List<GroupMember> current = core.activeMembers(sourceId);
        if (!moving.stream().map(GroupMember::getId).collect(java.util.stream.Collectors.toSet())
                .equals(current.stream().map(GroupMember::getId).collect(java.util.stream.Collectors.toSet()))) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_CHANGED", "Group membership changed; retry the merge");
        }
        if (core.size(targetId) + current.size() > scope.getSubject().getMaxGroupSize()) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL", "The merged group would exceed 5 members");
        }
        Instant now = Instant.now();
        for (GroupMember member : current) {
            core.remove(member, GroupMember.LeftReason.MOVED, null, now, false);
            core.enroll(target, member.getEnrollment(), false, now);
        }
        core.dissolve(source, StudentGroup.DissolveReason.MODERATOR, actor.getId(), now);
        audit.record("StudentGroup", targetId, AuditAction.MODERATOR_MERGED_GROUPS, actor, scope.semesterCode(),
                Map.of("source", sourceId.toString(), "target", targetId.toString()));
        notifier.publish(DomainEvent.of(DomainEventType.MOVED_BY_MODERATOR,
                "Moderator đã gộp nhóm " + source.getName() + " vào nhóm " + target.getName(),
                core.activeMembers(targetId).stream().map(m -> m.getUser().getId()).toList())
                .about(targetId, null).by(actor.getId()));
        return target;
    }

    /** Dissolves the given groups at once (e.g. every group below 3 members, from the filtered list) (7.1). */
    @Transactional
    public int dissolve(List<UUID> groupIds, String reason, User actor) {
        if (groupIds == null || groupIds.isEmpty()) {
            throw new BadRequestException("Choose the groups to dissolve");
        }
        Instant now = Instant.now();
        int count = 0;
        for (UUID id : groupIds.stream().distinct().sorted().toList()) {
            StudentGroup group = core.get(id);
            if (group.isDissolved()) {
                continue;
            }
            FormationScope scope = requireCoordinating(group.getScope(), actor);
            group = core.lock(id);
            core.dissolve(group, StudentGroup.DissolveReason.MODERATOR, actor.getId(), now);
            audit.record("StudentGroup", group.getId(), AuditAction.GROUP_DISSOLVED, actor, scope.semesterCode(),
                    Map.of("group", group.getCode(), "reason", reason == null ? "" : reason.trim()));
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ helpers

    /** Held shared, so finalizing (which takes the scope exclusively) cannot interleave with a coordination change. */
    private FormationScope requireCoordinating(FormationScope scope, User actor) {
        access.requireModerator(scope.semesterCode(), actor);
        FormationScope current = locks.read(FormationScope.class, scope.getId());
        rules.requireNotFinalized(current);
        return current;
    }

    private Enrollment enrollmentOf(FormationScope scope, UUID userId) {
        Enrollment e = enrollments.findBySubjectIdAndUserId(scope.getSubject().getId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_ENROLLED",
                        "The student is not on the list of this subject"));
        if (!e.getScope().getId().equals(scope.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "OTHER_MAJOR", "Groups are formed within one major only");
        }
        return e;
    }

    private void requireGroupable(Enrollment e) {
        if (e.isIneligible()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_ELIGIBLE",
                    e.getUser().getFullName() + " is not eligible for this subject");
        }
        if (core.membershipOf(e.getId()) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_GROUP",
                    e.getUser().getFullName() + " already belongs to a group; move them instead");
        }
    }

    private void notifyMembers(StudentGroup group, String message, User actor, UUID except) {
        if (group.isDissolved()) {
            return;
        }
        notifier.publish(DomainEvent.of(DomainEventType.MEMBER_JOINED, message,
                core.activeMembers(group.getId()).stream().map(m -> m.getUser().getId())
                        .filter(id -> !id.equals(except)).toList()).about(group.getId(), null).by(actor.getId()));
    }

    private static String code(Enrollment e) {
        return String.valueOf(e.getUser().getStudentCode());
    }
}
