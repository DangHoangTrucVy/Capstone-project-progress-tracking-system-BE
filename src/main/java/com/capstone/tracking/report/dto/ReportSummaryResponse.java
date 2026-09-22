package com.capstone.tracking.report.dto;

/** Matches API-011's response shape. */
public record ReportSummaryResponse(long sessionsHeld, double attendanceRate, long openReqs, long closedReqs) {
}
