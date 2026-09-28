package com.capstone.tracking.user.dto;

import com.capstone.tracking.user.Campus;
import com.capstone.tracking.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Used by Admin to create accounts directly (FR-010), e.g. bulk-importing instructors/council members/students.
 * password is optional: accounts that only sign in with the school's Google Workspace need none.
 */
public record UserCreateRequest(
        @NotBlank @Email String email,
        @NotBlank String fullName,
        @Size(min = 8, message = "Password must be at least 8 characters") String password,
        @NotNull Role role,
        Campus campus
) {
}
