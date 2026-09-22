package com.capstone.tracking.evaluation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Matches API-010's payload. Scores are 0-100 per blueprint.md §8/§9. */
public record EvaluationCreateRequest(
        @Min(0) @Max(100) int topicFitScore,
        @Min(0) @Max(100) int productQualityScore,
        @Min(0) @Max(100) int communicationScore,
        String feedback
) {
}
