package com.capstone.tracking.artifact.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * JSON form of API-005 for LINK documents (GitHub, demo, Drive...). Files go through the multipart form
 * of the same endpoint instead.
 */
public record ArtifactCreateRequest(
        @NotBlank String title,
        @NotBlank String fileUrl,
        String fileType,
        UUID sessionId,
        UUID milestoneId
) {
}
