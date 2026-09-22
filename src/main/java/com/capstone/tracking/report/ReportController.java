package com.capstone.tracking.report;

import com.capstone.tracking.report.dto.ReportSummaryResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sprint 5 — API-011 / US-008: weekly progress dashboard summary. No "Dept Head" role exists in
 * the Role enum, so this is scoped to Admin only. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Periodic progress statistics")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/api/v1/reports/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ReportSummaryResponse summary(@RequestParam String semester,
                                          @RequestParam(required = false) Integer weekNumber) {
        return reportService.summary(semester, weekNumber);
    }
}
