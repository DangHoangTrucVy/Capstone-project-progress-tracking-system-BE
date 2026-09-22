package com.capstone.tracking.meeting.dto;

import com.capstone.tracking.meeting.MeetingSession;
import com.capstone.tracking.meeting.SessionStatus;

import java.time.Instant;
import java.util.UUID;

public record MeetingSessionResponse(
        UUID id,
        UUID bookingId,
        Instant startedAt,
        Instant endedAt,
        String rawNotes,
        SessionStatus sessionStatus
) {
    public static MeetingSessionResponse from(MeetingSession s) {
        return new MeetingSessionResponse(
                s.getId(),
                s.getBooking().getId(),
                s.getStartedAt(),
                s.getEndedAt(),
                s.getRawNotes(),
                s.getSessionStatus());
    }
}
