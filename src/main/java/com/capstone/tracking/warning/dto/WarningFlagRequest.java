package com.capstone.tracking.warning.dto;

import com.capstone.tracking.warning.WarningFlagType;
import com.capstone.tracking.warning.WarningSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** memberId is required for MEMBER_INACTIVE and must be an active member of the group. */
public record WarningFlagRequest(
        @NotNull WarningFlagType type,
        @NotNull WarningSeverity severity,
        @NotBlank String reason,
        UUID memberId
) {
}
