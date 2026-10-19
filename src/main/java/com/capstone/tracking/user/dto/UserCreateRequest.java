package com.capstone.tracking.user.dto;

import com.capstone.tracking.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An Admin creates an account directly (another Admin, or a single lecturer outside the list import). password is
 * optional: accounts that only sign in with Google need none. Students come from the student list import.
 */
public record UserCreateRequest(
        @NotBlank @Email String email,
        @NotBlank String fullName,
        @Size(min = 8, message = "Password must be at least 8 characters") String password,
        @NotNull Role role,
        @Size(max = 20) String phone
) {
}
