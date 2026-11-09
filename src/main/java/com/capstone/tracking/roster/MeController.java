package com.capstone.tracking.roster;

import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterPhase;
import com.capstone.tracking.semester.SemesterRepository;
import com.capstone.tracking.team.GroupCore;
import com.capstone.tracking.team.GroupMember;
import com.capstone.tracking.team.RoomStatus;
import com.capstone.tracking.team.StudentGroup;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The student's home: every semester they are listed in, newest first (where they land after signing in; older ones
 * are view-only, mục 9), with the semester's phase and deadline, their status and their room.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Tag(name = "Profile")
@SecurityRequirement(name = "bearerAuth")
public class MeController {

    private final SemesterStudentRepository semesterStudents;
    private final EnrollmentRepository enrollments;
    private final SemesterRepository semesters;
    private final GroupCore core;

    public record MyRoom(UUID id, String code, String name, int size, int minSize, int maxSize, RoomStatus status,
                         boolean leader, boolean finalized, Instant finalizedAt) {}

    /**
     * {@code canJoinRooms}: free to join/leave rooms now (formation running, before the deadline, eligible, newest
     * semester). {@code canAcceptInvites}: the supplement phase and still without a room.
     */
    public record MySemester(String semesterCode, String semesterName, SemesterPhase phase, String phaseLabel,
                             Instant deadline, boolean beforeDeadline, UUID majorId, String majorCode, String majorName,
                             int currentTerm, boolean readOnly, boolean canJoinRooms, boolean canAcceptInvites,
                             UUID enrollmentId, Eligibility eligibility, String eligibilityReason,
                             NoGroupReason noGroupReason, MyRoom room) {}

    @Operation(summary = "My semesters, newest first, with phase, deadline, my status and my room (students)")
    @GetMapping("/semesters")
    @Transactional(readOnly = true)
    public List<MySemester> mySemesters(@AuthenticationPrincipal User currentUser) {
        Instant now = Instant.now();
        List<MySemester> result = new ArrayList<>();
        boolean first = true;
        for (SemesterStudent ss : semesterStudents.findByUserNewestFirst(currentUser.getId())) {
            Semester semester = semesters.findById(ss.getSemesterCode()).orElseThrow();
            boolean readOnly = !first;
            first = false;
            Enrollment e = enrollments.findBySemesterStudentId(ss.getId()).orElse(null);
            GroupMember m = e == null ? null : core.membershipOf(e.getId());
            MyRoom room = null;
            if (m != null) {
                StudentGroup g = m.getGroup();
                int size = (int) core.size(g.getId());
                room = new MyRoom(g.getId(), g.getCode(), g.getName(), size, g.getMinSize(), semester.getMaxGroupSize(),
                        core.status(g, size, semester, now), m.isLeader(), g.isFinalized(), g.getFinalizedAt());
            }
            boolean active = !readOnly && e != null && !e.isIneligible();
            result.add(new MySemester(semester.getCode(), semester.getName(), semester.getPhase(),
                    semester.getPhase().label(), semester.getDeadline(), semester.isBeforeDeadline(now),
                    ss.getMajor().getId(), ss.getMajor().getCode(), ss.getMajor().getName(), ss.getCurrentTerm(),
                    readOnly, active && semester.isBeforeDeadline(now),
                    active && m == null && semester.getPhase() == SemesterPhase.SUPPLEMENT,
                    e == null ? null : e.getId(), e == null ? null : e.getEligibility(),
                    e == null ? null : e.getEligibilityReason(), m == null && e != null ? e.getNoGroupReason() : null,
                    room));
        }
        return result;
    }
}
