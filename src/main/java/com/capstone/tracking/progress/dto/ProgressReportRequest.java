package com.capstone.tracking.progress.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Create and full update of a weekly report; on update {@code tasks} replaces the whole task list. */
public record ProgressReportRequest(
        @NotNull @Min(1) @Max(15) Integer weekNumber,
        @NotNull @Min(0) @Max(100) Integer progressPercentage,
        String summary,
        String blockers,
        String nextWeekPlan,
        List<@Valid ProgressTaskRequest> tasks
) {
}
