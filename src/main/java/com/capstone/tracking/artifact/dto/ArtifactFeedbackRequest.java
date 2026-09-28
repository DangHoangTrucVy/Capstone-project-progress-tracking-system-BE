package com.capstone.tracking.artifact.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The supervisor either accepts this version or asks the leader to submit a revision. */
public record ArtifactFeedbackRequest(
        @NotBlank @Size(max = 10000) String feedback,
        @NotNull Boolean accepted
) {}
