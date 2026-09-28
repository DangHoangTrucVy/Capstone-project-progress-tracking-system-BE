package com.capstone.tracking.proposal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Round 1 must list exactly 10 topics; resubmissions (rounds 2-4) 1 to 10 (checked in TopicProposalService). */
public record ProposalSubmitRequest(
        @NotEmpty @Size(max = 10) List<@Valid Item> topics
) {
    public record Item(
            @NotBlank @Size(max = 255) String title,
            String description
    ) {
    }
}
