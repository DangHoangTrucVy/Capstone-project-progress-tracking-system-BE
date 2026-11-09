package com.capstone.tracking.semester;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.Locks;
import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.team.GroupCore;
import com.capstone.tracking.team.GroupMember;
import com.capstone.tracking.team.GroupMemberRepository;
import com.capstone.tracking.team.JoinRequest;
import com.capstone.tracking.team.JoinRequestRepository;
import com.capstone.tracking.team.LeaderTransferRepository;
import com.capstone.tracking.team.LeaveRequestRepository;
import com.capstone.tracking.team.StudentGroup;
import com.capstone.tracking.team.StudentGroupRepository;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The life of a semester (3.5, 3.6, 7): the Moderator starts formation, the deadline locks the rooms automatically, the
 * Moderator extends the deadline (before round 1), finalizes round 1 (to the supplement phase, or straight to the end
 * when nobody is left), finalizes round 2, and formation ends: every remaining room becomes a coded group and empty
 * rooms are closed. Every transition holds the semester row exclusively; roster actions hold it shared.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhaseService {

    private final SemesterRepository semesters;
    private final FormationScopeRepository scopes;
    private final EnrollmentRepository enrollments;
    private final StudentGroupRepository groups;
    private final GroupMemberRepository members;
    private final JoinRequestRepository joinRequests;
    private final LeaveRequestRepository leaveRequests;
    private final LeaderTransferRepository transfers;
    private final GroupCore core;
    private final Access access;
    private final Notifier notifier;
    private final AuditService audit;
    private final Locks locks;
    private final FormationTiming timing;

    public record RoomRef(UUID id, String name, String code, String majorCode, long size, int minSize) {}

    public record MajorRef(UUID id, String code, String name) {}

    /** What blocks each transition from the current phase, and the warnings that do not block (3.6, 7.3, 7.5). */
    public record PhaseCheck(SemesterPhase phase, Instant deadline, boolean beforeDeadline, Instant roomsLockedAt,
                             boolean canStartForming, List<String> startBlockers,
                             boolean canFinalizeRound1, List<String> round1Blockers,
                             boolean canSkipSupplement,
                             boolean canFinalizeRound2, List<String> round2Blockers,
                             List<String> warnings, List<RoomRef> roomsBelowMinimum, List<MajorRef> majorsWithoutRooms,
                             List<RoomRef> pendingFinalizeRequests, long openComplaints, long studentsWithoutGroup) {}

    // ------------------------------------------------------------------ checks

    public PhaseCheck check(String code, User viewer) {
        Semester semester = access.requireModerator(code, viewer);
        return check(semester, Instant.now());
    }

    PhaseCheck check(Semester s, Instant now) {
        String code = s.getCode();
        List<StudentGroup> rooms = groups.findActiveBySemester(code);
        Map<UUID, Long> sizes = sizes(rooms);
        List<RoomRef> belowMin = rooms.stream()
                .filter(g -> !g.isApproved())
                .filter(g -> sizes.getOrDefault(g.getId(), 0L) > 0 && sizes.getOrDefault(g.getId(), 0L) < g.getMinSize())
                .map(g -> ref(g, sizes)).toList();
        List<RoomRef> pending = rooms.stream().filter(StudentGroup::isFinalizeRequested).map(g -> ref(g, sizes)).toList();
        long complaints = leaveRequests.countOpenInSemester(code);
        long ungrouped = enrollments.findWithoutGroupInSemester(code).size();
        long students = enrollments.countBySemester(code);
        List<MajorRef> withoutRooms = new ArrayList<>();
        for (FormationScope scope : scopes.findBySemester(code)) {
            if (enrollments.countByScopeId(scope.getId()) > 0 && groups.findActiveByScope(scope.getId()).isEmpty()) {
                withoutRooms.add(new MajorRef(scope.getMajor().getId(), scope.getMajor().getCode(), scope.getMajor().getName()));
            }
        }

        List<String> start = new ArrayList<>();
        if (s.getPhase() != SemesterPhase.OPENING) {
            start.add("The semester is already in phase " + s.getPhase());
        }
        if (students == 0) {
            start.add("The semester has no students yet");
        }
        if (s.getDeadline() == null) {
            start.add("The deadline is not set");
        } else if (!now.isBefore(s.getDeadline())) {
            start.add("The deadline (" + VnTime.format(s.getDeadline()) + ") has passed; set a new one");
        }
        if (!withoutRooms.isEmpty()) {
            start.add(withoutRooms.size() + " major(s) have students but no room: "
                    + String.join(", ", withoutRooms.stream().map(MajorRef::code).toList()));
        }

        List<String> common = new ArrayList<>();
        if (!belowMin.isEmpty()) {
            common.add(belowMin.size() + " room(s) have members but fewer than their minimum; disband them or fill them");
        }
        if (complaints > 0) {
            common.add(complaints + " leave request(s) or complaint(s) are not handled yet");
        }
        if (!pending.isEmpty()) {
            common.add(pending.size() + " finalize request(s) are waiting for an answer");
        }
        List<String> round1 = new ArrayList<>();
        if (s.getPhase() != SemesterPhase.FORMING) {
            round1.add("Round 1 is finalized in phase FORMING (phase is " + s.getPhase() + ")");
        } else if (s.getDeadline() == null || now.isBefore(s.getDeadline())) {
            round1.add("The deadline has not passed yet; rooms are still open");
        }
        round1.addAll(common);
        List<String> round2 = new ArrayList<>();
        if (s.getPhase() != SemesterPhase.SUPPLEMENT) {
            round2.add("Round 2 is finalized in phase SUPPLEMENT (phase is " + s.getPhase() + ")");
        }
        round2.addAll(common);

        List<String> warnings = new ArrayList<>();
        if (ungrouped > 0) {
            warnings.add(ungrouped + " eligible or undecided student(s) have no group");
        }
        return new PhaseCheck(s.getPhase(), s.getDeadline(), s.isBeforeDeadline(now), s.getRoomsLockedAt(),
                start.isEmpty(), start, round1.isEmpty(), round1, round1.isEmpty() && ungrouped == 0,
                round2.isEmpty(), round2, warnings, belowMin, withoutRooms, pending, complaints, ungrouped);
    }

    // ------------------------------------------------------------------ transitions

    /** OPENING -> FORMING (3.6): students, a future deadline and at least one room in every major with students. */
    @Transactional
    public Semester startForming(String code, User actor) {
        access.requireModerator(code, actor);
        Semester semester = locks.writeSemester(code);
        Instant now = Instant.now();
        PhaseCheck check = check(semester, now);
        if (!check.canStartForming()) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_START", String.join("; ", check.startBlockers()));
        }
        semester.setPhase(SemesterPhase.FORMING);
        semester.setFormingStartedAt(now);
        semester.setRoomsLockedAt(null);
        audit.record("Semester", entityId(code), AuditAction.FORMING_STARTED, actor, code,
                details("deadline", semester.getDeadline()));
        notifyStudents(semester, DomainEventType.FORMING_STARTED, "Học kỳ " + code
                + " đã mở giai đoạn Lập nhóm. Hãy vào một Room của ngành mình trước deadline "
                + VnTime.format(semester.getDeadline()), semester.getDeadline(), actor);
        return semester;
    }

    /**
     * Sets or extends the deadline (3.6, 3.7): before round 1 only, at least the minimum notice from now. When the
     * rooms were already locked by the old deadline they open again until the new one (gia hạn). Students are told.
     */
    @Transactional
    public Semester setDeadline(String code, Instant deadline, User actor) {
        access.requireModerator(code, actor);
        Semester semester = locks.writeSemester(code);
        if (semester.getPhase() != SemesterPhase.OPENING && semester.getPhase() != SemesterPhase.FORMING) {
            throw new ApiException(HttpStatus.CONFLICT, "AFTER_ROUND1",
                    "The deadline can only change before round 1 is finalized");
        }
        Instant now = Instant.now();
        requireNotice(deadline, now);
        if (semester.getFormationOpensAt() != null && !deadline.isAfter(semester.getFormationOpensAt())) {
            throw new BadRequestException("The deadline must come after the opening date");
        }
        Instant before = semester.getDeadline();
        boolean reopened = semester.getRoomsLockedAt() != null;
        semester.setDeadline(deadline);
        semester.setRoomsLockedAt(null);
        audit.record("Semester", entityId(code), reopened ? AuditAction.DEADLINE_EXTENDED : AuditAction.DEADLINE_CHANGED,
                actor, code, details("from", before, "to", deadline));
        if (semester.getPhase() == SemesterPhase.FORMING || before != null) {
            notifyStudents(semester, reopened ? DomainEventType.SCOPE_EXTENDED : DomainEventType.DEADLINE_CHANGED,
                    (reopened ? "Ghép nhóm học kỳ " + code + " được gia hạn, các Room mở lại tới "
                            : "Deadline ghép nhóm học kỳ " + code + " đổi thành ") + VnTime.format(deadline),
                    deadline, actor);
        }
        return semester;
    }

    /**
     * Round 1 (7.3), at the end of FORMING after the deadline: refused while a room with members is below its minimum,
     * a complaint or leave request is open, or a finalize request waits. Then the supplement phase opens and the
     * rooms below the maximum are told students need a group (7.4) - or, with {@code skipSupplement} and nobody left
     * without a group, formation ends at once (3.6).
     */
    @Transactional
    public Semester finalizeRound1(String code, boolean skipSupplement, User actor) {
        access.requireModerator(code, actor);
        Semester semester = locks.writeSemester(code);
        Instant now = Instant.now();
        if (semester.getPhase() == SemesterPhase.FORMING && semester.getRoomsLockedAt() == null
                && semester.getDeadline() != null && !now.isBefore(semester.getDeadline())) {
            lockRooms(semester, now); // the scheduler has not run yet
        }
        PhaseCheck check = check(semester, now);
        if (!check.canFinalizeRound1()) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_FINALIZE", String.join("; ", check.round1Blockers()));
        }
        if (skipSupplement && check.studentsWithoutGroup() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "STUDENTS_LEFT", check.studentsWithoutGroup()
                    + " student(s) still have no group; open the supplement phase instead");
        }
        semester.setRound1FinalizedAt(now);
        semester.setRound1FinalizedBy(actor.getId());
        audit.record("Semester", entityId(code), AuditAction.ROUND1_FINALIZED, actor, code,
                details("studentsWithoutGroup", check.studentsWithoutGroup(), "skipSupplement", skipSupplement,
                        "warnings", check.warnings()));
        if (skipSupplement) {
            close(semester, actor, now);
            return semester;
        }
        semester.setPhase(SemesterPhase.SUPPLEMENT);
        notifyStudents(semester, DomainEventType.SUPPLEMENT_OPENED, "Học kỳ " + code
                + " đã chốt đợt 1 và mở giai đoạn Bổ sung: các nhóm còn chỗ có thể mời sinh viên chưa có nhóm", null, actor);
        announceStudentsNeedingGroup(semester, actor);
        return semester;
    }

    /** Round 2 (7.5): the same criteria as round 1; then formation ends (7.6). */
    @Transactional
    public Semester finalizeRound2(String code, User actor) {
        access.requireModerator(code, actor);
        Semester semester = locks.writeSemester(code);
        Instant now = Instant.now();
        PhaseCheck check = check(semester, now);
        if (!check.canFinalizeRound2()) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_FINALIZE", String.join("; ", check.round2Blockers()));
        }
        semester.setRound2FinalizedAt(now);
        semester.setRound2FinalizedBy(actor.getId());
        audit.record("Semester", entityId(code), AuditAction.ROUND2_FINALIZED, actor, code,
                details("studentsWithoutGroup", check.studentsWithoutGroup(), "warnings", check.warnings()));
        close(semester, actor, now);
        return semester;
    }

    /**
     * The end of formation (7.6): open requests, invites and transfers are cancelled; every room with members that is
     * not a group yet becomes one, numbered after the groups approved earlier, in room order; empty rooms are closed.
     * Never reopened (3.6).
     */
    private void close(Semester semester, User actor, Instant now) {
        String code = semester.getCode();
        joinRequests.cancelPendingOfSemester(code, JoinRequest.CancelReason.CLOSED, now);
        transfers.cancelPendingOfSemester(code, now);
        int coded = 0;
        int closed = 0;
        for (FormationScope scope : scopes.findBySemester(code)) {
            for (StudentGroup room : groups.findActiveByScope(scope.getId())) {
                StudentGroup locked = core.lock(room.getId());
                if (core.size(locked.getId()) == 0) {
                    core.close(locked, now);
                    closed++;
                } else if (!locked.isApproved()) {
                    core.finalizeRoom(locked, actor.getId(), now);
                    coded++;
                }
            }
        }
        semester.setPhase(SemesterPhase.CLOSED);
        semester.setClosedAt(now);
        audit.record("Semester", entityId(code), AuditAction.FORMATION_CLOSED, actor, code,
                details("roomsBecameGroups", coded, "emptyRoomsClosed", closed));
        notifyStudents(semester, DomainEventType.FORMATION_CLOSED, "Học kỳ " + code
                + " đã kết thúc ghép nhóm. Danh sách nhóm đã được chốt.", null, actor);
    }

    // ------------------------------------------------------------------ automatic: the deadline (7.1)

    /** The scheduler's step for one semester: lock the rooms once the deadline is reached. Own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tick(String code) {
        Semester semester = locks.writeSemester(code);
        Instant now = Instant.now();
        if (semester.getPhase() == SemesterPhase.FORMING && semester.getRoomsLockedAt() == null
                && semester.getDeadline() != null && !now.isBefore(semester.getDeadline())) {
            lockRooms(semester, now);
        }
    }

    @Transactional
    public int expireRequests() {
        return joinRequests.expireDue(Instant.now());
    }

    /**
     * The deadline (7.1): every room not approved is locked, open requests and invites are cancelled, leave requests
     * waiting for a Leader become complaints to the Moderator, transfer-and-leave offers are void. Rooms below their
     * minimum are not disbanded automatically: the Moderator does it (7.2).
     */
    private void lockRooms(Semester semester, Instant now) {
        String code = semester.getCode();
        semester.setRoomsLockedAt(now);
        int cancelled = joinRequests.cancelPendingOfSemester(code, JoinRequest.CancelReason.DEADLINE, now);
        int complaints = leaveRequests.escalatePendingOfSemester(code);
        transfers.cancelPendingLeavesOfSemester(code, now);
        PhaseCheck check = check(semester, now);
        audit.record("Semester", entityId(code), AuditAction.ROOMS_LOCKED, null, code,
                details("cancelledRequests", cancelled, "complaints", complaints,
                        "roomsBelowMinimum", check.roomsBelowMinimum().size(),
                        "studentsWithoutGroup", check.studentsWithoutGroup()));
        notifyStudents(semester, DomainEventType.SCOPE_LOCKED, "Đã tới deadline ghép nhóm học kỳ " + code
                + ". Các Room đã bị khóa; Moderator sẽ sắp xếp các bạn chưa có nhóm.", null, null);
        notifier.publish(DomainEvent.of(DomainEventType.COORDINATION_NEEDED,
                "Học kỳ " + code + " đã khóa Room theo deadline: " + check.roomsBelowMinimum().size()
                        + " Room dưới sĩ số tối thiểu, " + check.studentsWithoutGroup() + " sinh viên chưa có nhóm, "
                        + check.openComplaints() + " khiếu nại, " + check.pendingFinalizeRequests().size()
                        + " yêu cầu chốt cần xử lý",
                notifier.coordinators(code)));
        log.info("Semester {} rooms locked at the deadline: {} requests cancelled, {} complaints", code, cancelled,
                complaints);
    }

    // ------------------------------------------------------------------ helpers

    /** 7.4 / 8.3: rooms below the maximum hear that students need a group; those students hear invites may come. */
    private void announceStudentsNeedingGroup(Semester semester, User actor) {
        List<Enrollment> left = enrollments.findWithoutGroupInSemester(semester.getCode());
        if (left.isEmpty()) {
            return;
        }
        Map<UUID, List<Enrollment>> byScope = new HashMap<>();
        left.forEach(e -> byScope.computeIfAbsent(e.getScope().getId(), k -> new ArrayList<>()).add(e));
        for (Map.Entry<UUID, List<Enrollment>> entry : byScope.entrySet()) {
            List<StudentGroup> rooms = groups.findActiveByScope(entry.getKey());
            Map<UUID, Long> sizes = sizes(rooms);
            List<UUID> leaders = new ArrayList<>();
            for (StudentGroup room : rooms) {
                long size = sizes.getOrDefault(room.getId(), 0L);
                if (size > 0 && size < semester.getMaxGroupSize()) {
                    GroupMember leader = core.leaderOf(room.getId());
                    if (leader != null) {
                        leaders.add(leader.getUser().getId());
                    }
                }
            }
            List<Enrollment> students = entry.getValue();
            String names = String.join(", ", students.stream()
                    .map(e -> e.getUser().getFullName() + " (" + e.getUser().getStudentCode() + ")").toList());
            notifier.publish(DomainEvent.of(DomainEventType.STUDENTS_NEED_GROUP,
                    "Giai đoạn Bổ sung: " + students.size() + " sinh viên ngành "
                            + students.get(0).getScope().getMajor().getCode() + " chưa có nhóm: " + names
                            + ". Nhóm nào đồng ý nhận có thể gửi lời mời.", leaders).by(actor.getId()));
            notifier.publish(DomainEvent.of(DomainEventType.STUDENTS_NEED_GROUP,
                    "Bạn chưa có nhóm. Các nhóm còn chỗ đã được thông báo và có thể gửi lời mời cho bạn.",
                    students.stream().map(e -> e.getUser().getId()).toList()).by(actor.getId()));
        }
    }

    public void requireNotice(Instant deadline, Instant now) {
        if (deadline == null) {
            throw new BadRequestException("The deadline is required");
        }
        if (deadline.isBefore(now.plus(timing.minimumDeadlineNotice()))) {
            throw new BadRequestException("A new deadline must be at least " + timing.minDeadlineNoticeMinutes()
                    + " minutes from now");
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

    private static RoomRef ref(StudentGroup g, Map<UUID, Long> sizes) {
        return new RoomRef(g.getId(), g.getName(), g.getCode(), g.getScope().getMajor().getCode(),
                sizes.getOrDefault(g.getId(), 0L), g.getMinSize());
    }

    private void notifyStudents(Semester semester, DomainEventType type, String message, Instant deadline, User actor) {
        List<UUID> students = enrollments.findBySemester(semester.getCode()).stream().map(e -> e.getUser().getId()).toList();
        notifier.publish(DomainEvent.of(type, message, students).withDetails(null, deadline)
                .by(actor == null ? null : actor.getId()));
    }

    public Semester get(String code) {
        return semesters.findById(code).orElseThrow(() -> ResourceNotFoundException.of("Semester", code));
    }

    static UUID entityId(String code) {
        return UUID.nameUUIDFromBytes(("semester:" + code).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, Object> details(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1] == null ? null : pairs[i + 1].toString());
        }
        return map;
    }
}
