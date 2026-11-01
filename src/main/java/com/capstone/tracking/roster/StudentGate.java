package com.capstone.tracking.roster;

import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Whether a student may act in a subject: they must be on its list, in their most recent semester (older semesters
 * of a retaking student are view-only, mục 9) and not ineligible (ineligible students only view, 5.2).
 */
@Component
@RequiredArgsConstructor
public class StudentGate {

    private final EnrollmentRepository enrollments;
    private final SemesterStudentRepository semesterStudents;

    public Enrollment enrollment(UUID subjectId, User user) {
        return enrollments.findBySubjectIdAndUserId(subjectId, user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_ENROLLED",
                        "You are not on the list of this subject"));
    }

    public Enrollment requireCanAct(UUID subjectId, User user) {
        return requireCanAct(enrollment(subjectId, user));
    }

    public Enrollment requireCanAct(Enrollment enrollment) {
        User user = enrollment.getUser();
        if (user.getRole() != Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_STUDENT", "Only students form groups");
        }
        List<SemesterStudent> newestFirst = semesterStudents.findByUserNewestFirst(user.getId());
        if (!newestFirst.isEmpty()
                && !newestFirst.get(0).getSemesterCode().equals(enrollment.getScope().semesterCode())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "READ_ONLY_SEMESTER",
                    "This is an earlier semester of yours; it can only be viewed");
        }
        requireEligible(enrollment);
        return enrollment;
    }

    public void requireEligible(Enrollment enrollment) {
        if (enrollment.isIneligible()) {
            String reason = enrollment.getEligibilityReason();
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ELIGIBLE",
                    "You are not eligible for this subject" + (reason == null ? "" : ": " + reason));
        }
    }
}
