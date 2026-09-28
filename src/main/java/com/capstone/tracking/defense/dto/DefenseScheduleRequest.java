package com.capstone.tracking.defense.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DefenseScheduleRequest(
        @NotNull UUID groupId,
        @Min(1) @Max(2) int attempt,
        @NotNull Instant scheduledAt,
        @NotNull @Min(15) Integer durationMinutes,
        @NotBlank String room,
        @NotEmpty List<UUID> committeeIds,
        @NotNull UUID chairId
) {
}
