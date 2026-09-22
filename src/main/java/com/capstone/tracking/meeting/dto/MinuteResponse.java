package com.capstone.tracking.meeting.dto;

import com.capstone.tracking.meeting.MeetingMinute;
import com.capstone.tracking.meeting.MinuteStatus;

import java.time.Instant;
import java.util.UUID;

public record MinuteResponse(
        UUID id,
        UUID sessionId,
        MinuteStatus status,
        String generatedContent,
        String finalContent,
        Instant studentSignedAt,
        Instant instructorSignedAt
) {
    public static MinuteResponse from(MeetingMinute m) {
        return new MinuteResponse(
                m.getId(),
                m.getSession().getId(),
                m.getStatus(),
                m.getGeneratedContent(),
                m.getFinalContent(),
                m.getStudentSignedAt(),
                m.getInstructorSignedAt());
    }
}
