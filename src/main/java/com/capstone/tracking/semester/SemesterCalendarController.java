package com.capstone.tracking.semester;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/semesters")
public class SemesterCalendarController {
    private final SemesterCalendarService service;
    public record CalendarRequest(@NotNull LocalDate startDate) {}

    @PutMapping("/{semester}") @PreAuthorize("hasRole('ADMIN')")
    public SemesterCalendar save(@PathVariable String semester,
                                 @Valid @RequestBody CalendarRequest request) {
        return service.save(semester, request.startDate());
    }

    @GetMapping("/{semester}")
    public SemesterCalendar get(@PathVariable String semester) { return service.get(semester); }
}
