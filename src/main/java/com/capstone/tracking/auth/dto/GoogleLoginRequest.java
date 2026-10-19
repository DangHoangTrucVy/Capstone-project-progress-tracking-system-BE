package com.capstone.tracking.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** The ID token Google Identity Services returned to the FE's sign-in button. */
public record GoogleLoginRequest(@NotBlank String idToken) {
}
