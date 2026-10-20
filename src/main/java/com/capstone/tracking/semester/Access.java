package com.capstone.tracking.semester;

import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Server-side permission checks by role and semester (12). The Admin may do everything the Moderator does (and does it
 * when no Moderator is assigned); a lecturer coordinates only the semester they are assigned to.
 */
@Component
@RequiredArgsConstructor
public class Access {

    private final SemesterRepository semesters;

    public boolean isAdmin(User user) {
        return user != null && user.getRole() == Role.ADMIN;
    }

    public boolean isModerator(Semester semester, User user) {
        return isAdmin(user) || (user != null && user.getRole() == Role.LECTURER
                && user.isLecturerImported() && user.getId().equals(semester.getModeratorId()));
    }

    public boolean isModerator(String semesterCode, User user) {
        return isAdmin(user) || semesters.findById(semesterCode).map(s -> isModerator(s, user)).orElse(false);
    }

    public Semester requireModerator(String semesterCode, User user) {
        Semester semester = semesters.findById(semesterCode)
                .orElseThrow(() -> ResourceNotFoundException.of("Semester", semesterCode));
        requireModerator(semester, user);
        return semester;
    }

    public void requireModerator(Semester semester, User user) {
        if (!isModerator(semester, user)) {
            throw new AccessDeniedException("Only the Moderator of semester " + semester.getCode() + " or an Admin may do this");
        }
    }

    public void requireAdmin(User user) {
        if (!isAdmin(user)) {
            throw new AccessDeniedException("Only an Admin may do this");
        }
    }
}
