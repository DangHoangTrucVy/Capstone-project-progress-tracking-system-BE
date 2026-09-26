package com.capstone.tracking.progress.dto;

import com.capstone.tracking.progress.TaskStatus;
import com.capstone.tracking.progress.WeeklyProgressReport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Built inside ProgressService's transaction: it reads the lazy tasks and user names. */
public record ProgressReportResponse(
        UUID id,
        UUID groupId,
        int weekNumber,
        int progressPercentage,
        String summary,
        String blockers,
        String nextWeekPlan,
        List<ProgressTaskResponse> tasks,
        int totalTasks,
        int doneTasks,
        UUID submittedById,
        String submittedByName,
        String instructorFeedback,
        String feedbackByName,
        Instant feedbackAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProgressReportResponse from(WeeklyProgressReport r) {
        List<ProgressTaskResponse> tasks = r.getTasks().stream().map(ProgressTaskResponse::from).toList();
        int done = (int) tasks.stream().filter(t -> t.status() == TaskStatus.DONE).count();
        return new ProgressReportResponse(r.getId(), r.getGroup().getId(), r.getWeekNumber(), r.getProgressPercentage(),
                r.getSummary(), r.getBlockers(), r.getNextWeekPlan(), tasks, tasks.size(), done,
                r.getSubmittedBy().getId(), r.getSubmittedBy().getFullName(), r.getInstructorFeedback(),
                r.getFeedbackBy() != null ? r.getFeedbackBy().getFullName() : null, r.getFeedbackAt(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
