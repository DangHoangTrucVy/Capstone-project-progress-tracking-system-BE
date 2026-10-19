package com.capstone.tracking.user.dto;

import com.capstone.tracking.user.User;
import java.util.UUID;

/**
 * A student's profile as another person sees it (8.1). {@code full} false: the profile is private and the viewer has
 * no right to it, so only name and student code are shown.
 */
public record ProfileView(
        UUID userId,
        String fullName,
        String studentCode,
        boolean full,
        String email,
        String phone,
        String skills,
        String strengths,
        String weaknesses
) {
    public static ProfileView of(User u, boolean full) {
        return full
                ? new ProfileView(u.getId(), u.getFullName(), u.getStudentCode(), true, u.getEmail(), u.getPhone(),
                        u.getSkills(), u.getStrengths(), u.getWeaknesses())
                : new ProfileView(u.getId(), u.getFullName(), u.getStudentCode(), false, null, null, null, null, null);
    }

    /** Full unless the profile is private and {@code privileged} (teammate, applicant's group...) is false. */
    public static ProfileView visible(User u, boolean privileged) {
        return of(u, privileged || !u.isProfilePrivate());
    }
}
