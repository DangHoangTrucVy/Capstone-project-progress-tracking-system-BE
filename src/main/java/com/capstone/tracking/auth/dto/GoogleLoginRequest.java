package com.capstone.tracking.auth.dto;

import com.capstone.tracking.user.Campus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Giai đoạn 1: the campus chosen on the sign-in page plus the ID token Google Identity Services returned. */
public record GoogleLoginRequest(
        @NotBlank String idToken,
        @NotNull Campus campus
) {
}
