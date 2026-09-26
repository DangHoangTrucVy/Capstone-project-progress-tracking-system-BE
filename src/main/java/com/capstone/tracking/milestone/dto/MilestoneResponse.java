package com.capstone.tracking.milestone.dto;

import com.capstone.tracking.milestone.Milestone;

import java.time.Instant;
import java.util.UUID;

public record MilestoneResponse(
        UUID id,
        String code,
        String name,
        String description,
        String semester,
        Instant dueDate,
        int sequenceNo
) {
    public static MilestoneResponse from(Milestone m) {
        return new MilestoneResponse(m.getId(), m.getCode(), m.getName(), m.getDescription(), m.getSemester(),
                m.getDueDate(), m.getSequenceNo());
    }
}
