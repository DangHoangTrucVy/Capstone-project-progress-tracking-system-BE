package com.capstone.tracking.auth.dto;

import com.capstone.tracking.user.UserStatus;

import java.util.UUID;

/** Sign-up result: no token yet, the account waits for an Admin to approve it. */
public record RegisterResponse(UUID id, String email, UserStatus status, String message) {
}
