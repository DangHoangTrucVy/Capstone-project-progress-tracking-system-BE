package com.capstone.tracking.team;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.Locks;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.major.Major;
import com.capstone.tracking.major.MajorRepository;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.Eligibility;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.NoGroupReason;
import com.capstone.tracking.roster.RosterSync;
import com.capstone.tracking.roster.StudentGate;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.semester.FormationScope;
import com.capstone.tracking.semester.FormationScopeRepository;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterGuard;
import com.capstone.tracking.semester.SemesterPhase;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rooms (6): the Moderator creates them; students join and leave them freely before the deadline, the first one in is
 * the Leader; the Leader renames, locks, kicks and asks the Moderator to finalize; the Moderator approves (the room
 * becomes a coded group) or sends it back. Lock order everywhere: semester (shared), the student's enrollment, then
 * the room, then the scope (group numbering).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupService {

    public static final int MAX_ROOMS_PER_CALL = 200;

    private final StudentGroupRepository groups;
    private final FormationScopeRepository scopes;
    private final com.capstone.tracking.semester.SemesterRepository semesters;
    private final EnrollmentRepository enrollments;
    private final MajorRepository majors;
    private final LeaveRequestRepository leaveRequests;
    private final JoinRequestService joinRequests;
    private final RosterSync sync;
    private final GroupCore core;
    private final StudentGate gate;
    private final SemesterGuard guard;
    private final Access access;
    private final Notifier notifier;
    private final AuditService audit;
    private final Locks locks;

    public record RoomUpdate(String name, String wantedSkills, Integer minSize) {}

    public record CreateRooms(UUID majorId, Integer count, Integer minSize) {}

    /** Per major: students who can form groups, existing rooms and the suggested number of rooms (6.1). */
    public record RoomPlan(UUID majorId, String majorCode, String majorName, long students, long rooms,
                           long suggestedRooms, long missingRooms) {}

    /** The outcome of "join": the student joined at once, or (locked room) a request went to the Leader. */
    public record JoinOutcome(boolean joined, boolean leader, JoinRequest request) {}

    /** The outcome of "leave": either the student left at once, or a request went to the Leader or the Moderator. */
    public record LeaveOutcome(boolean left, LeaveRequest request) {}

    // ------------------------------------------------------------------ the Moderator creates rooms (6.1)

    /**
     * Creates {@code count} rooms in one major, named Room 01, Room 02... after the major's last room. Without a count,
     * the missing rooms of the plan: ceil(students / minimum room size) minus the existing rooms.
     */
    @Transactional
    public List<StudentGroup> createRooms(String semesterCode, CreateRooms request, User actor) {
        access.requireModerator(semesterCode, actor);
        Semester semester = locks.readSemester(semesterCode);
        guard.requireBeforeRound1(semester, "Creating rooms");
        if (request.majorId() == null) {
            throw new BadRequestException("Choose the major");
        }
        Major major = majors.findById(request.majorId()).orElseThrow(() -> ResourceNotFoundException.of("Major", request.majorId()));
        int minSize = request.minSize() == null ? semester.getDefaultMinRoomSize() : request.minSize();
        requireMinSize(semester, minSize);
        FormationScope scope = locks.write(FormationScope.class, sync.scopeFor(semesterCode, major).getId());
        int count = request.count() == null ? (int) plan(semester, scope).missingRooms() : request.count();
        if (count < 1 || count > MAX_ROOMS_PER_CALL) {
            throw new BadRequestException(request.count() == null
                    ? "Major " + major.getCode() + " already has enough rooms for its students; give a count to add more"
                    : "Create between 1 and " + MAX_ROOMS_PER_CALL + " rooms at once");
        }
        List<StudentGroup> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            scope.setRoomSeq(scope.getRoomSeq() + 1);
            created.add(groups.save(StudentGroup.builder()
                    .scope(scope)
                    .roomNo(scope.getRoomSeq())
                    .name(String.format("Room %02d", scope.getRoomSeq()))
                    .minSize(minSize)
                    .createdBy(actor.getId())
                    .build()));
        }
        groups.flush();
        audit.record("FormationScope", scope.getId(), AuditAction.ROOMS_CREATED, actor, semesterCode,
                Map.of("major", major.getCode(), "count", count, "minSize", minSize));
        return created;
    }

    public List<RoomPlan> plan(String semesterCode, User viewer) {
        access.requireModerator(semesterCode, viewer);
        Semester semester = semesters.findById(semesterCode).orElseThrow(() -> ResourceNotFoundException.of("Semester", semesterCode));
        List<RoomPlan> rows = new ArrayList<>();
        for (Major major : majorsOf(semesterCode)) {
            FormationScope scope = scopes.findBySemesterCodeAndMajorId(semesterCode, major.getId()).orElse(null);
            rows.add(scope == null ? new RoomPlan(major.getId(), major.getCode(), major.getName(), 0, 0, 0, 0)
                    : plan(semester, scope));
        }
        return rows;
    }

    private RoomPlan plan(Semester semester, FormationScope scope) {
        long students = enrollments.findByScopeId(scope.getId()).stream()
                .filter(e -> e.getEligibility() != Eligibility.INELIGIBLE).count();
        long rooms = groups.findActiveByScope(scope.getId()).size();
        long suggested = (students + semester.getDefaultMinRoomSize() - 1) / semester.getDefaultMinRoomSize();
        return new RoomPlan(scope.getMajor().getId(), scope.getMajor().getCode(), scope.getMajor().getName(), students,
                rooms, suggested, Math.max(0, suggested - rooms));
    }

    private List<Major> majorsOf(String semesterCode) {
        return scopes.findBySemester(semesterCode).stream().map(FormationScope::getMajor).toList();
    }

    // ------------------------------------------------------------------ settings

    public StudentGroup get(UUID groupId) {
        return core.get(groupId);
    }

    /**
     * The Leader renames the room or changes the skills it is looking for, before the deadline and before it is
     * approved; the Moderator renames any room and changes its minimum size (before round 1) until formation ends.
     */
    @Transactional
    public StudentGroup update(UUID groupId, RoomUpdate request, User user) {
        StudentGroup group = core.get(groupId);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        boolean moderator = access.isModerator(semester, user);
        if (moderator) {
            guard.requireNotClosed(semester);
        } else {
            gate.requireCanAct(semester.getCode(), user);
            guard.requireBeforeDeadline(semester);
        }
        group = core.lock(groupId);
        if (!moderator) {
            core.requireLeader(group, user.getId());
            requireNotApproved(group);
            if (request.minSize() != null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "MODERATOR_ONLY", "Only the Moderator changes a room's minimum size");
            }
        }
        if (request.name() != null) {
            group.setName(name(request.name()));
        }
        if (request.wantedSkills() != null) {
            group.setWantedSkills(skills(request.wantedSkills()));
        }
        if (request.minSize() != null && request.minSize() != group.getMinSize()) {
            guard.requireBeforeRound1(semester, "Changing a room's minimum size");
            requireMinSize(semester, request.minSize());
            audit.record("StudentGroup", group.getId(), AuditAction.ROOM_MIN_SIZE_CHANGED, user, semester.getCode(),
                    Map.of("room", group.getName(), "from", group.getMinSize(), "to", request.minSize()));
            group.setMinSize(request.minSize());
        }
        return group;
    }

    /** The Leader locks or unlocks the room, before the deadline only (6.3). */
    @Transactional
    public StudentGroup setLocked(UUID groupId, boolean locked, User user) {
        StudentGroup group = core.get(groupId);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        gate.requireCanAct(semester.getCode(), user);
        guard.requireBeforeDeadline(semester);
        group = core.lock(groupId);
        core.requireLeader(group, user.getId());
        requireNotApproved(group);
        group.setLocked(locked);
        if (!locked) {
            // Unlocked: anyone may now walk in, so the waiting requests are moot.
            joinRequestsCancelApplies(group);
        }
        return group;
    }

    /** The Leader removes a member before the deadline, while the room is not approved (6.1). */
    @Transactional
    public void kick(UUID groupId, UUID memberUserId, User user) {
        StudentGroup group = core.get(groupId);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        gate.requireCanAct(semester.getCode(), user);
        guard.requireBeforeDeadline(semester);
        GroupMember target = core.activeMembers(groupId).stream()
                .filter(m -> m.getUser().getId().equals(memberUserId)).findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("Member", memberUserId));
        core.lockEnrollment(target.getEnrollment().getId());
        group = core.lock(groupId);
        core.requireLeader(group, user.getId());
        requireNotApproved(group);
        requireNoFinalizeRequest(group);
        if (target.getUser().getId().equals(user.getId())) {
            throw new BadRequestException("A Leader cannot remove themselves; transfer the role or leave the room");
        }
        core.remove(target, GroupMember.LeftReason.KICKED, null, Instant.now(), true);
        notifier.publish(DomainEvent.of(DomainEventType.MEMBER_KICKED,
                "Bạn đã bị trưởng nhóm mời ra khỏi " + GroupCore.label(group), memberUserId)
                .about(group.getId(), null).by(user.getId()));
    }

    // ------------------------------------------------------------------ joining (6.2)

    /**
     * Joining a room before the deadline. An empty room: the student walks in and is its Leader. An open room: the
     * student walks in. A room the Leader locked: a request goes to the Leader (limited and with a lifetime by the
     * semester's rules). Full, approved or waiting-for-approval rooms take nobody.
     */
    @Transactional
    public JoinOutcome join(UUID groupId, String message, User user) {
        StudentGroup group = core.get(groupId);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        Enrollment enrollment = gate.requireCanAct(semester.getCode(), user);
        guard.requireBeforeDeadline(semester);
        if (!enrollment.getScope().getId().equals(group.getScope().getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "OTHER_MAJOR", "Rooms are formed within one major only");
        }
        enrollment = core.lockEnrollment(enrollment.getId());
        group = core.lock(groupId);
        if (core.groupOf(enrollment.getId()) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_GROUP", "You already belong to a room; leave it first");
        }
        requireNotApproved(group);
        requireNoFinalizeRequest(group);
        long size = core.size(groupId);
        if (size >= semester.getMaxGroupSize()) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL", "The room is full (" + semester.getMaxGroupSize() + " members)");
        }
        Instant now = Instant.now();
        if (size > 0 && group.isLocked()) {
            return new JoinOutcome(false, false, joinRequests.createApply(group, enrollment, message, semester, user, now));
        }
        GroupMember member = core.enroll(group, enrollment, size == 0, semester, now);
        if (member.isLeader()) {
            notifier.publish(DomainEvent.of(DomainEventType.BECAME_LEADER,
                    "Bạn là người đầu tiên vào " + GroupCore.label(group) + " và trở thành trưởng nhóm", user.getId())
                    .about(group.getId(), null));
        } else {
            notifier.publish(DomainEvent.of(DomainEventType.MEMBER_JOINED,
                    user.getFullName() + " đã vào " + GroupCore.label(group),
                    core.activeMembers(groupId).stream().map(m -> m.getUser().getId())
                            .filter(id -> !id.equals(user.getId())).toList()).about(group.getId(), null).by(user.getId()));
        }
        return new JoinOutcome(true, member.isLeader(), null);
    }

    // ------------------------------------------------------------------ leaving (6.5)

    /**
     * Leaving. Before the deadline, from a room that is neither locked nor approved, the student leaves at once (a
     * leaving Leader is replaced, 6.7; the last one out leaves the room empty). In a locked room a member asks the
     * Leader; the Leader must transfer the role or unlock first. After the deadline, and from an approved group, nobody
     * leaves by themselves: the request goes to the Moderator.
     */
    @Transactional
    public LeaveOutcome leave(UUID groupId, String reason, User user) {
        StudentGroup group = core.get(groupId);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        GroupMember member = core.activeMembers(groupId).stream()
                .filter(m -> m.getUser().getId().equals(user.getId())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_MEMBER", "You are not a member of this room"));
        Enrollment enrollment = core.lockEnrollment(member.getEnrollment().getId());
        gate.requireCanAct(enrollment);
        group = core.lock(groupId);
        guard.requireNotClosed(semester);
        if (semester.getPhase() == SemesterPhase.OPENING) {
            guard.requireBeforeDeadline(semester); // explains: formation has not started
        }
        Instant now = Instant.now();
        if (semester.isBeforeDeadline(now) && !group.isApproved()) {
            requireNoFinalizeRequest(group);
            if (!group.isLocked()) {
                core.remove(member, GroupMember.LeftReason.LEFT, NoGroupReason.LEFT_GROUP, now, true);
                notifier.publish(DomainEvent.of(DomainEventType.MEMBER_LEFT,
                        user.getFullName() + " đã rời " + GroupCore.label(group),
                        core.activeMembers(groupId).stream().map(m -> m.getUser().getId()).toList())
                        .about(group.getId(), null).by(user.getId()));
                return new LeaveOutcome(true, null);
            }
            if (member.isLeader()) {
                throw new ApiException(HttpStatus.CONFLICT, "LEADER_MUST_TRANSFER",
                        "The room is locked: transfer the Leader role (and leave) or unlock the room first");
            }
            return new LeaveOutcome(false, openLeaveRequest(group, enrollment, member, reason, LeaveRequest.Status.PENDING_LEADER));
        }
        // After the deadline, or an approved group: the Moderator decides (6.4, 6.5).
        return new LeaveOutcome(false, openLeaveRequest(group, enrollment, member, reason, LeaveRequest.Status.PENDING_MODERATOR));
    }

    private LeaveRequest openLeaveRequest(StudentGroup group, Enrollment enrollment, GroupMember member, String reason,
                                          LeaveRequest.Status status) {
        if (leaveRequests.findOpenByEnrollment(enrollment.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "LEAVE_REQUEST_OPEN", "You already have a pending leave request");
        }
        LeaveRequest request = leaveRequests.saveAndFlush(LeaveRequest.builder()
                .group(group)
                .enrollment(enrollment)
                .user(member.getUser())
                .status(status)
                .complaint(status == LeaveRequest.Status.PENDING_MODERATOR)
                .reason(blankToNull(reason))
                .build());
        String who = member.getUser().getFullName();
        if (status == LeaveRequest.Status.PENDING_LEADER) {
            GroupMember leader = core.leaderOf(group.getId());
            notifier.publish(DomainEvent.of(DomainEventType.LEAVE_REQUESTED, who + " xin rời " + GroupCore.label(group),
                    leader == null ? null : leader.getUser().getId()).about(group.getId(), request.getId())
                    .withDetails(request.getReason(), null).by(member.getUser().getId()));
        } else {
            notifier.publish(DomainEvent.of(DomainEventType.COMPLAINT_RECEIVED,
                    who + " (" + member.getUser().getStudentCode() + ") xin rời / chuyển khỏi " + GroupCore.label(group),
                    notifier.coordinators(group.getScope().semesterCode())).about(group.getId(), request.getId())
                    .withDetails(request.getReason(), null).by(member.getUser().getId()));
        }
        return request;
    }

    // ------------------------------------------------------------------ finalizing a room (6.4)

    /** The Leader asks the Moderator to finalize the room: before the deadline, with at least the room's minimum size. */
    @Transactional
    public StudentGroup requestFinalize(UUID groupId, User user) {
        StudentGroup group = core.get(groupId);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        gate.requireCanAct(semester.getCode(), user);
        guard.requireBeforeDeadline(semester);
        group = core.lock(groupId);
        core.requireLeader(group, user.getId());
        requireNotApproved(group);
        if (group.isFinalizeRequested()) {
            throw new ApiException(HttpStatus.CONFLICT, "FINALIZE_PENDING", "The request is already waiting for the Moderator");
        }
        long size = core.size(groupId);
        if (size < group.getMinSize()) {
            throw new ApiException(HttpStatus.CONFLICT, "BELOW_MINIMUM",
                    "The room needs at least " + group.getMinSize() + " members to be finalized (it has " + size + ")");
        }
        Instant now = Instant.now();
        group.setFinalizeStatus(StudentGroup.FinalizeStatus.REQUESTED);
        group.setFinalizeRequestedAt(now);
        group.setFinalizeRequestedBy(user.getId());
        group.setFinalizeNote(null);
        notifier.publish(DomainEvent.of(DomainEventType.ROOM_FINALIZE_REQUESTED,
                "Trưởng nhóm " + GroupCore.label(group) + " (ngành " + group.getScope().getMajor().getCode() + ", "
                        + size + " người) báo chốt nhóm", notifier.coordinators(semester.getCode()))
                .about(group.getId(), null).by(user.getId()));
        return group;
    }

    /** The Leader withdraws the finalize request before the Moderator answers. */
    @Transactional
    public StudentGroup cancelFinalize(UUID groupId, User user) {
        StudentGroup group = core.get(groupId);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        gate.requireCanAct(semester.getCode(), user);
        group = core.lock(groupId);
        core.requireLeader(group, user.getId());
        if (!group.isFinalizeRequested()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_FINALIZE_REQUEST", "There is no finalize request waiting");
        }
        group.setFinalizeStatus(StudentGroup.FinalizeStatus.NONE);
        group.setFinalizeRequestedAt(null);
        group.setFinalizeRequestedBy(null);
        return group;
    }

    /**
     * The Moderator approves (the room becomes a group with the next code at once, 6.4, 7.6) or sends it back with a
     * note. The Moderator may also approve a room that did not ask, e.g. after the deadline. The Leader and the
     * members are told.
     */
    @Transactional
    public StudentGroup decideFinalize(UUID groupId, boolean approve, String note, User actor) {
        StudentGroup group = core.get(groupId);
        access.requireModerator(group.getScope().semesterCode(), actor);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        guard.requireNotClosed(semester);
        group = core.lock(groupId);
        requireNotApproved(group);
        Instant now = Instant.now();
        long size = core.size(groupId);
        if (approve) {
            if (size < group.getMinSize() || size > semester.getMaxGroupSize()) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_SIZE", "A group needs between " + group.getMinSize()
                        + " and " + semester.getMaxGroupSize() + " members (this room has " + size + ")");
            }
            group.setFinalizeNote(blankToNull(note));
            core.finalizeRoom(group, actor.getId(), now);
            audit.record("StudentGroup", group.getId(), AuditAction.ROOM_FINALIZED, actor, semester.getCode(),
                    Map.of("room", group.getName(), "code", group.getCode(), "size", size));
        } else {
            if (!group.isFinalizeRequested()) {
                throw new ApiException(HttpStatus.CONFLICT, "NO_FINALIZE_REQUEST", "There is no finalize request to send back");
            }
            group.setFinalizeStatus(StudentGroup.FinalizeStatus.RETURNED);
            group.setFinalizeDecidedAt(now);
            group.setFinalizeDecidedBy(actor.getId());
            group.setFinalizeNote(blankToNull(note));
        }
        notifier.publish(DomainEvent.of(DomainEventType.ROOM_FINALIZE_DECIDED,
                approve ? group.getName() + " đã được Moderator duyệt chốt, mã nhóm " + group.getCode()
                        : "Moderator trả lại yêu cầu chốt " + group.getName(),
                core.activeMembers(groupId).stream().map(m -> m.getUser().getId()).toList())
                .about(group.getId(), null).withDetails(group.getFinalizeNote(), null).by(actor.getId()));
        return group;
    }

    // ------------------------------------------------------------------ queries

    /** Rooms of the semester: the Moderator sees all (filter by major), a student the rooms of their own major. */
    public List<StudentGroup> rooms(String semesterCode, UUID majorId, User viewer) {
        if (access.isModerator(semesterCode, viewer)) {
            return groups.findActiveBySemester(semesterCode).stream()
                    .filter(g -> majorId == null || g.getScope().getMajor().getId().equals(majorId)).toList();
        }
        Enrollment mine = gate.enrollment(semesterCode, viewer);
        return groups.findActiveByScope(mine.getScope().getId());
    }

    public List<StudentGroup> finalizeRequests(String semesterCode, User viewer) {
        access.requireModerator(semesterCode, viewer);
        return groups.findFinalizeRequested(semesterCode);
    }

    // ------------------------------------------------------------------ helpers

    private void joinRequestsCancelApplies(StudentGroup group) {
        joinRequests.cancelApplies(group, JoinRequest.CancelReason.ROOM_UNAVAILABLE);
    }

    static void requireNotApproved(StudentGroup group) {
        if (group.isApproved()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_FINALIZED",
                    "This room has been finalized as group " + group.getCode() + "; only the Moderator changes it");
        }
    }

    static void requireNoFinalizeRequest(StudentGroup group) {
        if (group.isFinalizeRequested()) {
            throw new ApiException(HttpStatus.CONFLICT, "FINALIZE_PENDING",
                    "The room is waiting for the Moderator to finalize it; the Leader may withdraw the request first");
        }
    }

    private static void requireMinSize(Semester semester, int minSize) {
        if (minSize < 1 || minSize > semester.getMaxGroupSize()) {
            throw new BadRequestException("A room's minimum size must be between 1 and the maximum group size ("
                    + semester.getMaxGroupSize() + ")");
        }
    }

    private static String name(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("The room name is required");
        }
        if (name.trim().length() > 100) {
            throw new BadRequestException("The room name is at most 100 characters");
        }
        return name.trim();
    }

    private static String skills(String text) {
        String skills = blankToNull(text);
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
}
