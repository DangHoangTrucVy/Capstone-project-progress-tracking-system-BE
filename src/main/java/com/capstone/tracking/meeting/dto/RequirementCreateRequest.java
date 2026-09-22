package com.capstone.tracking.meeting.dto;

import com.capstone.tracking.meeting.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Matches API-007's payload. */
public record RequirementCreateRequest(
        @NotBlank String title,
        String description,
        @NotNull Priority priority
) {
}
