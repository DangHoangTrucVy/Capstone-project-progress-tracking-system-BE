package com.capstone.tracking.report;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.report.dto.ReportSummaryResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportService reportService;
    private final HandoverExportService handoverExportService;

    @Operation(summary = "Progress statistics summary")
    @GetMapping("/api/v1/reports/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ReportSummaryResponse summary(@RequestParam String semester,
                                          @RequestParam(required = false) Integer weekNumber) {
        return reportService.summary(semester, weekNumber);
    }

    /** "Xuất danh sách bàn giao": sheets Danh_sach_nhom and Chua_co_nhom; no semester = every semester. */
    @Operation(summary = "Export the handover list (Excel: Danh_sach_nhom, Chua_co_nhom)")
    @GetMapping("/api/v1/reports/handover/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportHandover(@RequestParam(required = false) String semester) {
        String name = "danh_sach_ban_giao" + (semester == null || semester.isBlank()
                ? "" : "_" + semester.trim().replaceAll("[^A-Za-z0-9_-]", "")) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(handoverExportService.export(semester));
    }
}
