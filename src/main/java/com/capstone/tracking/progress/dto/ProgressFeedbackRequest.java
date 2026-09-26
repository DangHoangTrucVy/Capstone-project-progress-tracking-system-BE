package com.capstone.tracking.progress.dto;

import jakarta.validation.constraints.NotBlank;

public record ProgressFeedbackRequest(@NotBlank String feedback) {
}
