package com.capstone.tracking.milestone.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record MilestoneCreateRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        @NotBlank String semester,
        Instant dueDate,
        @NotNull @Min(1) Integer sequenceNo
) {
}
