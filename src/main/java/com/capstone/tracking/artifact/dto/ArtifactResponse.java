package com.capstone.tracking.artifact.dto;

import com.capstone.tracking.artifact.ArtifactSourceType;
import com.capstone.tracking.artifact.ArtifactStatus;
import com.capstone.tracking.artifact.ArtifactSubmission;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code fileUrl} is always something the client can open: the external link for LINK documents, or the
 * backend download endpoint for FILE ones (it needs the Bearer token like every other API call).
 * Only ids are read from the lazy associations, which Hibernate serves without loading them.
 */
public record ArtifactResponse(
        UUID id,
        UUID groupId,
        UUID sessionId,
        UUID milestoneId,
        String title,
        ArtifactSourceType sourceType,
        String fileUrl,
        String fileType,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        int version,
        Instant submittedAt,
        UUID submittedById,
        ArtifactStatus status
) {
    public static ArtifactResponse from(ArtifactSubmission a) {
        return new ArtifactResponse(
                a.getId(),
                a.getGroup().getId(),
                a.getSession() != null ? a.getSession().getId() : null,
                a.getMilestone() != null ? a.getMilestone().getId() : null,
                a.getTitle(),
                a.getSourceType(),
                a.getSourceType() == ArtifactSourceType.FILE ? "/api/v1/artifacts/" + a.getId() + "/file" : a.getFileUrl(),
                a.getFileType(),
                a.getOriginalFilename(),
                a.getContentType(),
                a.getSizeBytes(),
                a.getVersion(),
                a.getSubmittedAt(),
                a.getSubmittedBy() != null ? a.getSubmittedBy().getId() : null,
                a.getStatus());
    }
}
