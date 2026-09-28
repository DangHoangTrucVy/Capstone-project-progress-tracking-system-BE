package com.capstone.tracking.review.dto;

import com.capstone.tracking.review.ClosedCouncilOutcome;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** outcome is required for Review 3 and ignored otherwise; revisionDeadline only applies to REVISE_BEFORE_DEFENSE_1. */
public record ReviewResultRequest(
        @NotBlank String feedback,
        ClosedCouncilOutcome outcome,
        Instant revisionDeadline
) {
}
