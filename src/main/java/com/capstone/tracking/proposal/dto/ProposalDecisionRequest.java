package com.capstone.tracking.proposal.dto;

import jakarta.validation.constraints.NotNull;

/** Bước 2.3 / 2.4: the Council's verdict. Feedback is required when rejecting. */
public record ProposalDecisionRequest(
        @NotNull Decision decision,
        String feedback
) {
    public enum Decision {
        APPROVED,
        REJECTED
    }
}
