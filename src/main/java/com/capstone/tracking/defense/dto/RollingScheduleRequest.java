package com.capstone.tracking.defense.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bước 6.1 "cuốn chiếu": one committee in one room defends the listed groups back to back, in order, starting at
 * firstStartTime with breakMinutes between groups — instead of all groups defending at once.
 */
public record RollingScheduleRequest(
        @Min(1) @Max(2) int attempt,
        @NotEmpty List<UUID> groupIds,
        @NotNull Instant firstStartTime,
        @NotNull @Min(15) Integer durationMinutes,
        @Min(0) int breakMinutes,
        @NotBlank String room,
        @NotEmpty List<UUID> committeeIds,
        @NotNull UUID chairId
) {
}
