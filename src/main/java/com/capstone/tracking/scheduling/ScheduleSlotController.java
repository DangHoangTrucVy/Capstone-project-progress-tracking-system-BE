package com.capstone.tracking.scheduling;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.scheduling.dto.SlotCreateRequest;
import com.capstone.tracking.scheduling.dto.SlotPage;
import com.capstone.tracking.scheduling.dto.SlotResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** API-001 / API-002. */
@RestController
@RequestMapping("/api/v1/slots")
@RequiredArgsConstructor
@Tag(name = "Schedule Slots", description = "Instructor availability, Calendly-style (Sprint 2)")
@SecurityRequirement(name = "bearerAuth")
public class ScheduleSlotController {

    private final ScheduleSlotService scheduleSlotService;

    @PostMapping
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public ResponseEntity<SlotResponse> create(@Valid @RequestBody SlotCreateRequest request,
                                                @AuthenticationPrincipal User currentUser) {
        ScheduleSlot created = scheduleSlotService.create(request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/slots/" + created.getId()))
                .body(SlotResponse.from(created));
    }

    @GetMapping
    public Page<SlotResponse> search(@RequestParam(required = false) UUID instructorId,
                                      @RequestParam(required = false) SlotStatus status,
                                      @RequestParam(required = false) String fromDate,
                                      @RequestParam(required = false) String toDate,
                                      Pageable pageable) {
        SlotPage page = scheduleSlotService.searchPage(instructorId, status, parseInstant(fromDate), parseInstant(toDate), pageable);
        return new PageImpl<>(page.content(), pageable, page.totalElements());
    }

    /** Query params come in as plain ISO-8601 strings (e.g. 2026-10-01T08:00:00Z) and are parsed explicitly
     * here rather than relying on Spring's Instant binding, which is inconsistent across MVC versions. */
    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid date-time format: " + value + " (expected ISO-8601, e.g. 2026-10-01T08:00:00Z)");
        }
    }

    @GetMapping("/{id}")
    public SlotResponse getById(@PathVariable UUID id) {
        return scheduleSlotService.getResponse(id);
    }
}
