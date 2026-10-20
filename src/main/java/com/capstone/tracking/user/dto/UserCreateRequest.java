package com.capstone.tracking.user.dto;

import com.capstone.tracking.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin creates a Moderator (internal role LECTURER) with a password. Other roles are rejected.
 * Admin is provisioned in the database; students come from the student roster and use Google only.
 */
public record UserCreateRequest(
        @NotBlank @Email String email,
        @NotBlank String fullName,
        @NotBlank @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters") String password,
        @NotNull Role role,
        @Size(max = 20) String phone
) {
}
