package com.capstone.tracking.warning.dto;

import com.capstone.tracking.warning.WarningFlag;
import com.capstone.tracking.warning.WarningFlagType;
import com.capstone.tracking.warning.WarningSeverity;

import java.time.Instant;
import java.util.UUID;

public record WarningFlagResponse(
        UUID id,
        UUID groupId,
        WarningFlagType type,
        WarningSeverity severity,
        String reason,
        UUID memberId,
        String memberName,
        UUID raisedById,
        String raisedByName,
        Instant raisedAt,
        boolean active,
        String resolvedByName,
        Instant resolvedAt,
        String resolutionNote
) {
    public static WarningFlagResponse from(WarningFlag f) {
        return new WarningFlagResponse(f.getId(), f.getGroup().getId(), f.getType(), f.getSeverity(), f.getReason(),
                f.getMember() != null ? f.getMember().getId() : null,
                f.getMember() != null ? f.getMember().getFullName() : null,
                f.getRaisedBy().getId(), f.getRaisedBy().getFullName(), f.getRaisedAt(), f.isActive(),
                f.getResolvedBy() != null ? f.getResolvedBy().getFullName() : null,
                f.getResolvedAt(), f.getResolutionNote());
    }
}
