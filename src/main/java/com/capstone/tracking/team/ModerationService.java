package com.capstone.tracking.team;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.Locks;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.NoGroupReason;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.semester.FormationScope;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterGuard;
import com.capstone.tracking.semester.SemesterPhase;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The assigned Moderator's coordination (7.2) in every phase until formation ends, locked rooms included, without
 * requests, invites or the Leader's consent: put students into rooms (the first one in an empty room is its Leader),
 * move them between rooms, take them out (with a reason, approved groups too), merge rooms, disband rooms back to empty
 * and appoint Leaders. Approved groups take new members only in the supplement phase (7.1, 7.4). Every change is
 * recorded and the students and rooms affected are told.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModerationService {

    private final StudentGroupRepository groups;
    private final EnrollmentRepository enrollments;
    private final GroupMemberRepository members;
    private final GroupCore core;
    private final SemesterGuard guard;
    private final Access access;
    private final Notifier notifier;
    private final AuditService audit;
    private final Locks locks;
    private final LeaderTransferRepository transfers;

    public record DisbandResult(int rooms, int students) {}

    /** A student without a room joins a room below the maximum size; into an empty room they come as its Leader. */
    @Transactional
    public GroupMember addMember(UUID groupId, UUID studentUserId, User actor) {
        StudentGroup group = core.get(groupId);
        Semester semester = coordinating(group, actor);
        Enrollment enrollment = core.lockEnrollment(enrollmentOf(group.getScope(), studentUserId).getId());
        requireGroupable(enrollment);
        group = core.lock(groupId);
        requireTakesMembers(group, semester);
        Instant now = Instant.now();
        GroupMember member = core.enroll(group, enrollment, false, semester, now);
        audit.record("StudentGroup", group.getId(), AuditAction.MODERATOR_ADDED_MEMBER, actor, semester.getCode(),
                Map.of("student", code(enrollment), "room", group.getName(), "leader", member.isLeader()));
        notifier.publish(DomainEvent.of(DomainEventType.ADDED_BY_MODERATOR,
                "Moderator đã xếp bạn vào " + GroupCore.label(group) + (member.isLeader() ? " với vai trò trưởng nhóm" : ""),
                enrollment.getUser().getId()).about(group.getId(), null).by(actor.getId()));
        notifyMembers(group, enrollment.getUser().getFullName() + " được Moderator xếp vào " + GroupCore.label(group),
                actor, enrollment.getUser().getId());
        return member;
    }

    /**
     * Moves a student from their room to another of the same major. A moved Leader is replaced in the old room (6.7);
     * an old room left empty goes back to empty. A room that falls below its minimum is not disbanded here.
     */
    @Transactional
    public GroupMember move(UUID toGroupId, UUID studentUserId, User actor) {
        StudentGroup target = core.get(toGroupId);
        Semester semester = coordinating(target, actor);
        Enrollment enrollment = core.lockEnrollment(enrollmentOf(target.getScope(), studentUserId).getId());
        GroupMember current = core.membershipOf(enrollment.getId());
        if (current == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_GROUP", "The student has no room; add them instead");
        }
        if (current.getGroup().getId().equals(toGroupId)) {
            throw new BadRequestException("The student is already in this room");
        }
        // Both rooms are locked in id order, so two opposite moves cannot deadlock.
        List<UUID> ids = new ArrayList<>(List.of(current.getGroup().getId(), toGroupId));
        ids.sort(Comparator.naturalOrder());
        Map<UUID, StudentGroup> locked = new HashMap<>();
        for (UUID id : ids) {
            locked.put(id, core.lock(id));
        }
        StudentGroup from = locked.get(current.getGroup().getId());
        StudentGroup to = locked.get(toGroupId);
        requireTakesMembers(to, semester);
        int max = semester.getMaxGroupSize();
        if (core.size(to.getId()) >= max) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL", "The room is full (" + max + " members)");
        }
        Instant now = Instant.now();
        boolean wasLeader = current.isLeader();
        core.remove(current, GroupMember.LeftReason.MOVED, null, now, true);
        GroupMember member = core.enroll(to, enrollment, false, semester, now);
        audit.record("StudentGroup", to.getId(), AuditAction.MODERATOR_MOVED_MEMBER, actor, semester.getCode(),
                Map.of("student", code(enrollment), "from", from.getName(), "to", to.getName(), "wasLeader", wasLeader));
        notifier.publish(DomainEvent.of(DomainEventType.MOVED_BY_MODERATOR,
                "Moderator đã chuyển bạn từ " + GroupCore.label(from) + " sang " + GroupCore.label(to),
                enrollment.getUser().getId()).about(to.getId(), null).by(actor.getId()));
        notifyMembers(from, enrollment.getUser().getFullName() + " được Moderator chuyển sang Room khác", actor, null);
        notifyMembers(to, enrollment.getUser().getFullName() + " được Moderator xếp vào " + GroupCore.label(to), actor,
                enrollment.getUser().getId());
        return member;
    }

    /** Takes a student out of any room, approved groups included; a reason is required (7.2). */
    @Transactional
    public void removeMember(UUID groupId, UUID studentUserId, String reason, User actor) {
        StudentGroup group = core.get(groupId);
        Semester semester = coordinating(group, actor);
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required");
        }
        Enrollment enrollment = core.lockEnrollment(enrollmentOf(group.getScope(), studentUserId).getId());
        group = core.lock(groupId);
        GroupMember member = core.membershipOf(enrollment.getId());
        if (member == null || !member.getGroup().getId().equals(groupId)) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_MEMBER", "The student is not in this room");
        }
        Instant now = Instant.now();
        core.remove(member, GroupMember.LeftReason.REMOVED_BY_MODERATOR, NoGroupReason.REMOVED_BY_MODERATOR, now, true);
        audit.record("StudentGroup", group.getId(), AuditAction.MODERATOR_REMOVED_MEMBER, actor, semester.getCode(),
                Map.of("student", code(enrollment), "room", group.getName(), "reason", reason.trim()));
        notifier.publish(DomainEvent.of(DomainEventType.REMOVED_BY_MODERATOR,
                "Moderator đã đưa bạn ra khỏi " + GroupCore.label(group), enrollment.getUser().getId())
                .about(group.getId(), null).withDetails(reason.trim(), null).by(actor.getId()));
        notifyMembers(group, enrollment.getUser().getFullName() + " đã được Moderator đưa ra khỏi nhóm: " + reason.trim(),
                actor, null);
    }

    /** The Moderator appoints a current member directly, without the transfer consent flow (7.2). */
    @Transactional
    public StudentGroup appointLeader(UUID groupId, UUID userId, User actor) {
        StudentGroup group = core.get(groupId);
        Semester semester = coordinating(group, actor);
        group = core.lock(groupId);
        List<GroupMember> active = core.activeMembers(groupId);
        GroupMember next = active.stream().filter(m -> m.getUser().getId().equals(userId)).findFirst()
                .orElseThrow(() -> new BadRequestException("The Leader must be a current member of this room"));
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
        audit.record("StudentGroup", groupId, AuditAction.MODERATOR_APPOINTED_LEADER, actor, semester.getCode(),
                Map.of("leader", userId.toString()));
        notifier.publish(DomainEvent.of(DomainEventType.LEADER_CHANGED,
                "Moderator đã chỉ định " + next.getUser().getFullName() + " làm trưởng " + GroupCore.label(group),
                active.stream().map(m -> m.getUser().getId()).toList()).about(groupId, null).by(actor.getId()));
        return group;
    }

    /** Merges the source room into the target; the source goes back to empty. Capacity is checked under the locks. */
    @Transactional
    public StudentGroup merge(UUID targetId, UUID sourceId, User actor) {
        if (sourceId == null || targetId.equals(sourceId)) {
            throw new BadRequestException("Choose a different source room");
        }
        StudentGroup target = core.get(targetId);
        Semester semester = coordinating(target, actor);
        StudentGroup source = core.get(sourceId);
        if (!source.getScope().getId().equals(target.getScope().getId())) {
            throw new BadRequestException("Rooms must belong to the same semester and major");
        }
        List<GroupMember> moving = core.activeMembers(sourceId);
        moving.stream().map(m -> m.getEnrollment().getId()).sorted().forEach(core::lockEnrollment);
        List.of(targetId, sourceId).stream().sorted().forEach(core::lock);
        target = core.lock(targetId);
        requireTakesMembers(target, semester);
        // Re-read under locks: concurrent leaves/joins may have changed the source since the first read.
        List<GroupMember> current = core.activeMembers(sourceId);
        if (!moving.stream().map(GroupMember::getId).collect(Collectors.toSet())
                .equals(current.stream().map(GroupMember::getId).collect(Collectors.toSet()))) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_CHANGED", "Room membership changed; retry the merge");
        }
        if (core.size(targetId) + current.size() > semester.getMaxGroupSize()) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL",
                    "The merged room would exceed " + semester.getMaxGroupSize() + " members");
        }
        Instant now = Instant.now();
        for (GroupMember member : current) {
            core.remove(member, GroupMember.LeftReason.MOVED, null, now, false);
            core.enroll(target, member.getEnrollment(), false, semester, now);
        }
        core.settle(source, now);
        audit.record("StudentGroup", targetId, AuditAction.MODERATOR_MERGED_GROUPS, actor, semester.getCode(),
                Map.of("source", source.getName(), "target", target.getName()));
        notifier.publish(DomainEvent.of(DomainEventType.MOVED_BY_MODERATOR,
                "Moderator đã gộp " + GroupCore.label(source) + " vào " + GroupCore.label(target),
                core.activeMembers(targetId).stream().map(m -> m.getUser().getId()).toList())
                .about(targetId, null).by(actor.getId()));
        return target;
    }

    /**
     * Disbands rooms back to empty (7.1, 7.2): the given ones, or with {@code allBelowMinimum} every room of the
     * semester (optionally one major) not approved and with members but fewer than its minimum ("giải tán tất cả Room
     * đang lọc"). Approved groups are never disbanded here; take members out one by one with a reason instead.
     */
    @Transactional
    public DisbandResult disband(String semesterCode, List<UUID> groupIds, boolean allBelowMinimum, UUID majorId,
                                 String reason, User actor) {
        access.requireModerator(semesterCode, actor);
        Semester semester = locks.readSemester(semesterCode);
        guard.requireNotClosed(semester);
        Set<UUID> ids = new LinkedHashSet<>();
        if (groupIds != null) {
            ids.addAll(groupIds);
        }
        if (allBelowMinimum) {
            List<StudentGroup> rooms = groups.findActiveBySemester(semesterCode).stream()
                    .filter(g -> majorId == null || g.getScope().getMajor().getId().equals(majorId)).toList();
            Map<UUID, Long> sizes = sizes(rooms);
            rooms.stream().filter(g -> !g.isApproved())
                    .filter(g -> sizes.getOrDefault(g.getId(), 0L) > 0 && sizes.getOrDefault(g.getId(), 0L) < g.getMinSize())
                    .forEach(g -> ids.add(g.getId()));
        }
        if (ids.isEmpty()) {
            if (allBelowMinimum) {
                return new DisbandResult(0, 0);
            }
            throw new BadRequestException("Choose the rooms to disband");
        }
        Instant now = Instant.now();
        int rooms = 0;
        int students = 0;
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        for (UUID id : ids.stream().sorted().toList()) {
            StudentGroup group = core.get(id);
            if (!group.getScope().semesterCode().equals(semesterCode)) {
                throw new BadRequestException("Room " + group.getName() + " is not in semester " + semesterCode);
            }
            group = core.lock(id);
            if (group.isApproved()) {
                throw new ApiException(HttpStatus.CONFLICT, "ROOM_FINALIZED",
                        "Group " + group.getCode() + " is finalized and cannot be disbanded");
            }
            int moved = core.disband(group, actor.getId(), why == null ? "bởi Moderator" : "bởi Moderator: " + why, now);
            audit.record("StudentGroup", group.getId(), AuditAction.GROUP_DISSOLVED, actor, semesterCode,
                    Map.of("room", group.getName(), "students", moved, "reason", why == null ? "" : why));
            rooms++;
            students += moved;
        }
        return new DisbandResult(rooms, students);
    }

    // ------------------------------------------------------------------ helpers

    /** The Moderator of the room's semester, which is held shared and must not be closed. */
    private Semester coordinating(StudentGroup group, User actor) {
        access.requireModerator(group.getScope().semesterCode(), actor);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        guard.requireNotClosed(semester);
        return semester;
    }

    /** Approved groups take new members only in the supplement phase (7.1: not before; 7.4: the leftovers). */
    private static void requireTakesMembers(StudentGroup group, Semester semester) {
        if (group.isApproved() && semester.getPhase() != SemesterPhase.SUPPLEMENT) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_FINALIZED",
                    "Group " + group.getCode() + " was finalized; it takes new members only in the supplement phase");
        }
    }

    private Enrollment enrollmentOf(FormationScope scope, UUID userId) {
        Enrollment e = enrollments.findBySemesterAndUser(scope.semesterCode(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_ENROLLED",
                        "The student is not on the list of this semester"));
        if (!e.getScope().getId().equals(scope.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "OTHER_MAJOR", "Rooms are formed within one major only");
        }
        return e;
    }

    private void requireGroupable(Enrollment e) {
        if (e.isIneligible()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_ELIGIBLE", e.getUser().getFullName() + " is not eligible");
        }
        if (core.membershipOf(e.getId()) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_GROUP",
                    e.getUser().getFullName() + " already belongs to a room; move them instead");
        }
    }

    private Map<UUID, Long> sizes(List<StudentGroup> rooms) {
        Map<UUID, Long> sizes = new HashMap<>();
        if (!rooms.isEmpty()) {
            members.findActiveIn(rooms.stream().map(StudentGroup::getId).toList())
                    .forEach(m -> sizes.merge(m.getGroup().getId(), 1L, Long::sum));
        }
        return sizes;
    }

    private void notifyMembers(StudentGroup group, String message, User actor, UUID except) {
        notifier.publish(DomainEvent.of(DomainEventType.ROOM_CHANGED_BY_MODERATOR, message,
                core.activeMembers(group.getId()).stream().map(m -> m.getUser().getId())
                        .filter(id -> !id.equals(except)).toList()).about(group.getId(), null).by(actor.getId()));
    }

    private static String code(Enrollment e) {
        return String.valueOf(e.getUser().getStudentCode());
    }
}
