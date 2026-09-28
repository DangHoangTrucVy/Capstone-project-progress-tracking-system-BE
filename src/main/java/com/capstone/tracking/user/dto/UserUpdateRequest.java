package com.capstone.tracking.user.dto;

import com.capstone.tracking.user.Campus;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** role/campus are optional: left null they keep their current value (Admin "phân quyền"). */
public record UserUpdateRequest(
        @NotBlank String fullName,
        String avatarUrl,
        @NotNull UserStatus status,
        Role role,
        Campus campus
) {
}
