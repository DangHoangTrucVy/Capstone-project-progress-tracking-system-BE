package com.capstone.tracking.proposal.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Bước 2.2: the supervisor picks the best topic of the list and forwards it to the Council. */
public record ProposalForwardRequest(
        @NotNull UUID itemId,
        String note
) {
}
