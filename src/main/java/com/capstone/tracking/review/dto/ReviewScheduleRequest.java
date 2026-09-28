package com.capstone.tracking.review.dto;

import com.capstone.tracking.review.ReviewRound;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Review 3 needs exactly 3 reviewers and a chairId among them; Reviews 1-2 take any panel, chair optional. */
public record ReviewScheduleRequest(
        @NotNull UUID groupId,
        @NotNull ReviewRound round,
        @NotNull Instant scheduledAt,
        @NotNull @Min(10) Integer durationMinutes,
        @NotBlank String location,
        @NotEmpty List<UUID> reviewerIds,
        UUID chairId
) {
}
