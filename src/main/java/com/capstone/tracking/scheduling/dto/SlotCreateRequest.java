package com.capstone.tracking.scheduling.dto;

import com.capstone.tracking.scheduling.LocationType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** Matches API-001's request payload. capacity may be omitted: one slot serves exactly one group (bước 3.1). */
public record SlotCreateRequest(
        @NotNull @Future Instant startTime,
        @NotNull @Future Instant endTime,
        @NotNull @Min(5) Integer durationMinutes,
        @Min(1) @Max(value = 1, message = "A slot serves exactly one group") Integer capacity,
        @NotNull LocationType locationType,
        String meetingUrl
) {
}
