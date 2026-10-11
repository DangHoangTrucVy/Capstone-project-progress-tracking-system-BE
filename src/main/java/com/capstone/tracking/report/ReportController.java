package com.capstone.tracking.report;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.report.dto.ReportSummaryResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final ReportService reportService;
    private final HandoverExportService handoverExportService;

    @Operation(summary = "Progress statistics summary")
    @GetMapping("/api/v1/reports/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ReportSummaryResponse summary(@RequestParam String semester,
                                          @RequestParam(required = false) Integer weekNumber) {
        return reportService.summary(semester, weekNumber);
    }

    /**
     * "Xuất danh sách bàn giao": sheets Danh_sach_nhom and Chua_co_nhom; no semester = every semester. The file is
     * named Danh_sach_ghep_nhom_[semester_]yyyyMMdd_HHmmss.xlsx (Vietnam time).
     */
    @Operation(summary = "Export the handover list (Excel: Danh_sach_nhom, Chua_co_nhom)")
    @GetMapping({"/api/v1/reports/export", "/api/v1/reports/handover/export"})
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportHandover(@RequestParam(required = false) String semester) {
        String term = semester == null ? "" : semester.trim().replaceAll("[^A-Za-z0-9_-]", "");
        String name = "Danh_sach_ghep_nhom_" + (term.isEmpty() ? "" : term + "_")
                + FILE_STAMP.format(Instant.now()) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString())
                .body(handoverExportService.export(semester));
    }
}
