package com.capstone.tracking.user.dto;

import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserStatus;
import java.time.Instant;
import java.util.UUID;

/** An account with its full profile; only for the account itself and Admins (others get a ProfileView). */
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        Role role,
        UserStatus status,
        String avatarUrl,
        String studentCode,
        String phone,
        String skills,
        String strengths,
        String weaknesses,
        boolean profilePrivate,
        Instant createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getStatus(), u.getAvatarUrl(),
                u.getStudentCode(), u.getPhone(), u.getSkills(), u.getStrengths(), u.getWeaknesses(),
                u.isProfilePrivate(), u.getCreatedAt());
    }
}
