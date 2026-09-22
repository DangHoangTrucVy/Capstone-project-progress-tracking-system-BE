package com.capstone.tracking.meeting;

import com.capstone.tracking.meeting.dto.EndSessionRequest;
import com.capstone.tracking.meeting.dto.MeetingSessionResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/** Sprint 4 — the MeetingSession lifecycle a Booking spawns (Scheduled -> In Progress -> Concluded). */
@RestController
@RequiredArgsConstructor
@Tag(name = "Meetings", description = "Meeting sessions started from a confirmed booking")
@SecurityRequirement(name = "bearerAuth")
public class MeetingSessionController {

    private final MeetingSessionService meetingSessionService;

    @PostMapping("/api/v1/bookings/{bookingId}/meetings")
    @PreAuthorize("hasAnyRole('GROUP_LEADER','INSTRUCTOR')")
    public ResponseEntity<MeetingSessionResponse> create(@PathVariable UUID bookingId,
                                                          @AuthenticationPrincipal User currentUser) {
        MeetingSession created = meetingSessionService.create(bookingId, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/meetings/" + created.getId()))
                .body(MeetingSessionResponse.from(created));
    }

    @GetMapping("/api/v1/meetings/{id}")
    public MeetingSessionResponse getById(@PathVariable UUID id) {
        return MeetingSessionResponse.from(meetingSessionService.getById(id));
    }

    @PutMapping("/api/v1/meetings/{id}/start")
    @PreAuthorize("hasAnyRole('GROUP_LEADER','INSTRUCTOR')")
    public MeetingSessionResponse start(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return MeetingSessionResponse.from(meetingSessionService.start(id, currentUser));
    }

    @PutMapping("/api/v1/meetings/{id}/end")
    @PreAuthorize("hasAnyRole('GROUP_LEADER','INSTRUCTOR')")
    public MeetingSessionResponse end(@PathVariable UUID id, @RequestBody(required = false) EndSessionRequest request,
                                       @AuthenticationPrincipal User currentUser) {
        return MeetingSessionResponse.from(meetingSessionService.end(id, request, currentUser));
    }
}
