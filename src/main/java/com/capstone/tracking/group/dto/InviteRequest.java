package com.capstone.tracking.group.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Who to invite: userId, email, identifier or student code (MSSV), like {@link AddMemberRequest}. */
public record InviteRequest(
        UUID userId,
        String email,
        String identifier,
        @JsonAlias({"mssv", "studentId"})
        String studentCode,
        @Size(max = 1000) String message
) {
}
