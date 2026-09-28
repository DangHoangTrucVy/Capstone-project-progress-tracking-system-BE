package com.capstone.tracking.review.dto;

import com.capstone.tracking.review.ReviewRound;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Bước 5.2: copy a round's schedule (time slot, room, panel) to another round, shifted by offsetDays — e.g. Review 1
 * (tuần 3-4) to Review 2 (tuần 7) with offsetDays = 28. Groups already scheduled in the target round are skipped.
 */
public record ReviewCloneRequest(
        @NotBlank String semester,
        @NotNull ReviewRound fromRound,
        @NotNull ReviewRound toRound,
        int offsetDays
) {
}
