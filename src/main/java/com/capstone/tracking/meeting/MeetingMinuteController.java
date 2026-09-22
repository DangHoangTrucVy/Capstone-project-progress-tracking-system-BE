package com.capstone.tracking.meeting;

import com.capstone.tracking.meeting.dto.MinuteGenerateRequest;
import com.capstone.tracking.meeting.dto.MinuteGenerateResponse;
import com.capstone.tracking.meeting.dto.MinuteResponse;
import com.capstone.tracking.meeting.dto.MinuteSignRequest;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Sprint 4 — API-008/API-009: auto-generated minutes, then a two-sided sign-off. */
@RestController
@RequestMapping("/api/v1/meetings/{id}/minutes")
@RequiredArgsConstructor
@Tag(name = "Meeting Minutes", description = "Auto-generated minutes and Leader/Instructor sign-off")
@SecurityRequirement(name = "bearerAuth")
public class MeetingMinuteController {

    private final MeetingMinuteService meetingMinuteService;

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('GROUP_LEADER','INSTRUCTOR')")
    public MinuteGenerateResponse generate(@PathVariable UUID id, @RequestBody(required = false) MinuteGenerateRequest request,
                                            @AuthenticationPrincipal User currentUser) {
        return meetingMinuteService.generate(id, request, currentUser);
    }

    @PutMapping("/sign")
    @PreAuthorize("hasAnyRole('GROUP_LEADER','INSTRUCTOR','ADMIN')")
    public MinuteResponse sign(@PathVariable UUID id, @Valid @RequestBody MinuteSignRequest request,
                                @AuthenticationPrincipal User currentUser) {
        return MinuteResponse.from(meetingMinuteService.sign(id, request, currentUser));
    }

    @GetMapping
    public MinuteResponse getBySession(@PathVariable UUID id) {
        return MinuteResponse.from(meetingMinuteService.getBySession(id));
    }
}
