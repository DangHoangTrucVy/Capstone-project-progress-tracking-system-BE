package com.capstone.tracking.artifact.dto;

import com.capstone.tracking.artifact.ArtifactStatus;
import com.capstone.tracking.artifact.ArtifactSubmission;

import java.time.Instant;
import java.util.UUID;

public record ArtifactResponse(
        UUID id,
        UUID groupId,
        UUID sessionId,
        String title,
        String fileUrl,
        String fileType,
        int version,
        Instant submittedAt,
        ArtifactStatus status
) {
    public static ArtifactResponse from(ArtifactSubmission a) {
        return new ArtifactResponse(
                a.getId(),
                a.getGroup().getId(),
                a.getSession() != null ? a.getSession().getId() : null,
                a.getTitle(),
                a.getFileUrl(),
                a.getFileType(),
                a.getVersion(),
                a.getSubmittedAt(),
                a.getStatus());
    }
}
