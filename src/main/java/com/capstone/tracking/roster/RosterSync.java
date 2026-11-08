package com.capstone.tracking.roster;

import com.capstone.tracking.major.Major;
import com.capstone.tracking.semester.FormationScope;
import com.capstone.tracking.semester.FormationScopeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps scopes and enrollments in line with the semester's student list: every imported student (term 8, 3.4) gets
 * one enrollment in the scope of their major, created on demand.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RosterSync {

    private final FormationScopeRepository scopes;
    private final EnrollmentRepository enrollments;

    public FormationScope scopeFor(String semesterCode, Major major) {
        return scopes.findBySemesterCodeAndMajorId(semesterCode, major.getId())
                .orElseGet(() -> scopes.saveAndFlush(FormationScope.builder().semesterCode(semesterCode).major(major).build()));
    }

    /**
     * After an import created or changed the student's record. A change of major is only possible while the semester
     * is in OPENING (the import refuses it later) and moves the enrollment to the new major's scope.
     */
    public Enrollment syncStudent(SemesterStudent ss) {
        FormationScope scope = scopeFor(ss.getSemesterCode(), ss.getMajor());
        Enrollment enrollment = enrollments.findBySemesterStudentId(ss.getId()).orElse(null);
        if (enrollment == null) {
            return enrollments.save(Enrollment.builder()
                    .scope(scope)
                    .semesterStudent(ss)
                    .user(ss.getUser())
                    .build());
        }
        if (!enrollment.getScope().getId().equals(scope.getId())) {
            enrollment.setScope(scope);
        }
        return enrollment;
    }
}
