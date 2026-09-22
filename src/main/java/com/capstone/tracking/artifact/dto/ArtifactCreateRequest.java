package com.capstone.tracking.artifact.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Matches API-005's payload, minus the multipart file itself: the client uploads the file to
 * storage first and submits the resulting {@code fileUrl} here (see ArtifactSubmission's javadoc —
 * this table never stores file bytes).
 */
public record ArtifactCreateRequest(
        @NotBlank String title,
        @NotBlank String fileUrl,
        String fileType,
        UUID sessionId
) {
}
