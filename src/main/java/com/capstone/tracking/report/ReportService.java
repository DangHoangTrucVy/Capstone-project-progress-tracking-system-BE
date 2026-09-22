package com.capstone.tracking.report;

import com.capstone.tracking.meeting.MeetingSession;
import com.capstone.tracking.meeting.MeetingSessionRepository;
import com.capstone.tracking.meeting.RequirementLog;
import com.capstone.tracking.meeting.RequirementLogRepository;
import com.capstone.tracking.meeting.RequirementStatus;
import com.capstone.tracking.meeting.SessionStatus;
import com.capstone.tracking.report.dto.ReportSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.List;

/**
 * Sprint 5 — API-011 / US-008. Week filtering is computed in Java via ISO week-of-year on each
 * row's own timestamp, rather than a DB-side date function, so the same logic runs identically
 * against Postgres in prod and H2 in tests. Blueprint.md leaves the exact semester/week scoping
 * underspecified; see README/the implementation plan for the concrete interpretation below.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final MeetingSessionRepository meetingSessionRepository;
    private final RequirementLogRepository requirementLogRepository;

    @Transactional(readOnly = true)
    public ReportSummaryResponse summary(String semester, Integer weekNumber) {
        List<MeetingSession> sessions = meetingSessionRepository.findByBooking_Group_Semester(semester);
        if (weekNumber != null) {
            sessions = sessions.stream()
                    .filter(s -> weekNumber.equals(isoWeek(sessionReferenceInstant(s))))
                    .toList();
        }
        long sessionsHeld = sessions.stream().filter(s -> s.getSessionStatus() == SessionStatus.CONCLUDED).count();
        double attendanceRate = sessions.isEmpty() ? 0.0 : (double) sessionsHeld / sessions.size();

        List<RequirementLog> requirements = requirementLogRepository.findByGroup_Semester(semester);
        long openReqs = requirements.stream()
                .filter(r -> r.getStatus() == RequirementStatus.OPEN || r.getStatus() == RequirementStatus.IN_PROGRESS)
                .count();
        long closedReqs = requirements.stream()
                .filter(r -> r.getStatus() == RequirementStatus.RESOLVED || r.getStatus() == RequirementStatus.CLOSED)
                .filter(r -> weekNumber == null || weekNumber.equals(isoWeek(r.getUpdatedAt())))
                .count();

        return new ReportSummaryResponse(sessionsHeld, attendanceRate, openReqs, closedReqs);
    }

    /** A session not yet started still "belongs" to the week its booked slot falls in. */
    private Instant sessionReferenceInstant(MeetingSession session) {
        return session.getStartedAt() != null ? session.getStartedAt() : session.getBooking().getSlot().getStartTime();
    }

    private int isoWeek(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).get(WeekFields.ISO.weekOfWeekBasedYear());
    }
}
