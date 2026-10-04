package com.capstone.tracking.semester;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/semesters")
@Tag(name = "Semesters")
@SecurityRequirement(name = "bearerAuth")
public class SemesterCalendarController {
    private final SemesterCalendarService service;
    public record CalendarRequest(@NotNull LocalDate startDate) {}

    @Operation(summary = "Set the semester start date")
    @PutMapping("/{semester}") @PreAuthorize("hasRole('ADMIN')")
    public SemesterCalendar save(@PathVariable String semester,
                                 @Valid @RequestBody CalendarRequest request) {
        return service.save(semester, request.startDate());
    }

    @Operation(summary = "Get the semester calendar (start date)")
    @GetMapping("/{semester}")
    public SemesterCalendar get(@PathVariable String semester) { return service.get(semester); }
}
