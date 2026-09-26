package com.capstone.tracking.progress.dto;

import com.capstone.tracking.progress.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** {@code assigneeId} must be an active member of the report's group. */
public record ProgressTaskRequest(
        @NotBlank String title,
        @NotNull TaskStatus status,
        UUID assigneeId
) {
}
