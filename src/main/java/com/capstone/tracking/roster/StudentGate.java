package com.capstone.tracking.roster;

import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Whether a student may act in a semester: they must be on its list, it must be their most recent semester (older
 * semesters of a retaking student are view-only, mục 9) and they must not be ineligible (ineligible students only
 * view, 5.2).
 */
@Component
@RequiredArgsConstructor
public class StudentGate {

    private final EnrollmentRepository enrollments;
    private final SemesterStudentRepository semesterStudents;

    public Enrollment enrollment(String semesterCode, User user) {
        return enrollments.findBySemesterAndUser(semesterCode, user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_ENROLLED",
                        "You are not on the student list of semester " + semesterCode));
    }

    public Enrollment requireCanAct(String semesterCode, User user) {
        return requireCanAct(enrollment(semesterCode, user));
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
                    "Not eligible for Capstone" + (reason == null ? "" : ": " + reason));
        }
    }
}
