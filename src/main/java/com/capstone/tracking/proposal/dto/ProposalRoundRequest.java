package com.capstone.tracking.proposal.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** Admin opens resubmission round 2-4 for a semester. opensAt defaults to now, closesAt to opensAt + 10 days. */
public record ProposalRoundRequest(
        @NotBlank String semester,
        @Min(2) @Max(4) int roundNumber,
        Instant opensAt,
        Instant closesAt
) {
}
