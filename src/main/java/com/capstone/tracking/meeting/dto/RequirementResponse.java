package com.capstone.tracking.meeting.dto;

import com.capstone.tracking.meeting.Priority;
import com.capstone.tracking.meeting.RequirementLog;
import com.capstone.tracking.meeting.RequirementStatus;

import java.time.Instant;
import java.util.UUID;

public record RequirementResponse(
        UUID id,
        UUID sessionId,
        UUID groupId,
        String title,
        String description,
        Priority priority,
        RequirementStatus status,
        UUID assignedTo,
        Instant createdAt
) {
    public static RequirementResponse from(RequirementLog r) {
        return new RequirementResponse(
                r.getId(),
                r.getSession().getId(),
                r.getGroup().getId(),
                r.getTitle(),
                r.getDescription(),
                r.getPriority(),
                r.getStatus(),
                r.getAssignedTo() != null ? r.getAssignedTo().getId() : null,
                r.getCreatedAt());
    }
}
