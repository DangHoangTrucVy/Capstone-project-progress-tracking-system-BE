package com.capstone.tracking.progress.dto;

import java.util.List;
import java.util.UUID;

/**
 * Group dashboard card: the latest week's numbers plus the week-by-week percentage series for a chart.
 * All latest* fields are null when the group has not reported yet.
 */
public record ProgressSummaryResponse(
        UUID groupId,
        int reportedWeeks,
        Integer latestWeek,
        Integer latestProgressPercentage,
        Integer latestTotalTasks,
        Integer latestDoneTasks,
        List<WeekPoint> trend
) {
    public record WeekPoint(int weekNumber, int progressPercentage) {
    }
}
