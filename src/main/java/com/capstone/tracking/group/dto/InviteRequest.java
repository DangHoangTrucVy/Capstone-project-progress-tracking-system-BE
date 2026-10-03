package com.capstone.tracking.group.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Who to invite: userId, email or student code (identifier), like {@link AddMemberRequest}. */
public record InviteRequest(
        UUID userId,
        String email,
        String identifier,
        @Size(max = 1000) String message
) {
}
