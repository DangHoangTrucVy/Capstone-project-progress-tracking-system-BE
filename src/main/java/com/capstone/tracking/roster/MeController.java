package com.capstone.tracking.roster;

import com.capstone.tracking.semester.SemesterRepository;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.ScopePhase;
import com.capstone.tracking.subject.ScopeRules;
import com.capstone.tracking.team.GroupCore;
import com.capstone.tracking.team.GroupMember;
import com.capstone.tracking.team.GroupState;
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
 * are view-only, mục 9), with each subject's phase, deadline, their three-part status and their group.
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
    private final ScopeRules rules;

    public record MyGroup(UUID id, String code, String name, int size, GroupState state, boolean leader, boolean finalized, Instant finalizedAt) {}

    public record MySubject(UUID subjectId, String code, String name, UUID scopeId, ScopePhase phase, Instant deadline,
                            boolean canAct, Eligibility eligibility, String eligibilityReason,
                            NoGroupReason noGroupReason, MyGroup group) {}

    public record MySemester(String semesterCode, String semesterName, String majorCode,
                             String majorName, int currentTerm, boolean readOnly, List<MySubject> subjects) {}

    @Operation(summary = "My semesters and subjects, newest semester first (students)")
    @GetMapping("/semesters")
    @Transactional(readOnly = true)
    public List<MySemester> mySemesters(@AuthenticationPrincipal User currentUser) {
        Instant now = Instant.now();
        List<MySemester> result = new ArrayList<>();
        boolean first = true;
        for (SemesterStudent ss : semesterStudents.findByUserNewestFirst(currentUser.getId())) {
            var semester = semesters.findById(ss.getSemesterCode()).orElseThrow();
            boolean readOnly = !first;
            List<MySubject> subjects = new ArrayList<>();
            for (Enrollment e : enrollments.findBySemesterStudentId(ss.getId())) {
                FormationScope scope = e.getScope();
                GroupMember m = core.membershipOf(e.getId());
                MyGroup group = m == null ? null : new MyGroup(m.getGroup().getId(), m.getGroup().getCode(),
                        m.getGroup().getName(), (int) core.size(m.getGroup().getId()),
                        core.state(m.getGroup(), core.size(m.getGroup().getId()), now), m.isLeader(), m.getGroup().isFinalized(), m.getGroup().getFinalizedAt());
                subjects.add(new MySubject(e.getSubjectId(), scope.getSubject().getCode(), scope.getSubject().getName(),
                        scope.getId(), scope.getPhase(), rules.deadline(scope),
                        !readOnly && !e.isIneligible() && rules.isOpen(scope, now), e.getEligibility(),
                        e.getEligibilityReason(), m == null ? e.getNoGroupReason() : null, group));
            }
            result.add(new MySemester(ss.getSemesterCode(), semester.getName(),
                    ss.getMajor().getCode(), ss.getMajor().getName(), ss.getCurrentTerm(), readOnly, subjects));
            first = false;
        }
        return result;
    }
}
