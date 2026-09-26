package com.capstone.tracking.scheduling.dto;

import com.capstone.tracking.scheduling.LocationType;
import com.capstone.tracking.scheduling.ScheduleSlot;
import com.capstone.tracking.scheduling.SlotStatus;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Matches API-002's response shape (plus a few extra fields IDs alone can't answer). */
public record SlotResponse(
        UUID id,
        UUID instructorId,
        String instructorName,
        Instant startTime,
        Instant endTime,
        int durationMinutes,
        int capacity,
        int bookedCount,
        LocationType locationType,
        String meetingUrl,
        SlotStatus status
) implements Serializable {
    public static SlotResponse from(ScheduleSlot s) {
        return new SlotResponse(s.getId(), s.getInstructor().getId(), s.getInstructor().getFullName(),
                s.getStartTime(), s.getEndTime(), s.getDurationMinutes(), s.getCapacityGroups(),
                s.getBookedCount(), s.getLocationType(), s.getMeetingUrl(), s.getStatus());
    }
}
