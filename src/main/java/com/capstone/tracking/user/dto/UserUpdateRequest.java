package com.capstone.tracking.user.dto;

import com.capstone.tracking.user.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin edits an account's contact details and status. The role does not change: it follows the imported lists. */
public record UserUpdateRequest(
        @NotBlank String fullName,
        @Email String email,
        @Size(max = 20) String phone,
        @NotNull UserStatus status
) {
}
