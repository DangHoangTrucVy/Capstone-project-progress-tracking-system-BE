package com.capstone.tracking.progress.dto;

import com.capstone.tracking.progress.ProgressTask;
import com.capstone.tracking.progress.TaskStatus;

import java.util.UUID;

public record ProgressTaskResponse(
        UUID id,
        String title,
        TaskStatus status,
        UUID assigneeId,
        String assigneeName
) {
    public static ProgressTaskResponse from(ProgressTask t) {
        return new ProgressTaskResponse(t.getId(), t.getTitle(), t.getStatus(),
                t.getAssignee() != null ? t.getAssignee().getId() : null,
                t.getAssignee() != null ? t.getAssignee().getFullName() : null);
    }
}
