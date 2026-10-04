package com.capstone.tracking.user.dto;

import com.capstone.tracking.user.Campus;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        Role role,
        UserStatus status,
        String avatarUrl,
        Campus campus,
        Instant createdAt,
        boolean eligible,
        String ineligibleReason,
        String bio,
        String skills,
        String studentCode,
        boolean selfRegistered,
        String rejectionReason
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getStatus(), u.getAvatarUrl(), u.getCampus(), u.getCreatedAt(),
                u.isEligible(), u.getIneligibleReason(), u.getBio(), u.getSkills(),
                u.getStudentCode(), u.isSelfRegistered(), u.getRejectionReason());
    }
}
