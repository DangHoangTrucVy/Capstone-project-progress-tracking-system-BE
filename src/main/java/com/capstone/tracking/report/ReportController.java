package com.capstone.tracking.report;

import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.audit.SystemAuditTrail;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/semesters/{semester}")
@RequiredArgsConstructor
@Tag(name = "Statistics & export", description = "Statistics (7.3), the handover Excel file (10) and the history (11)")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final StatisticsService statistics;
    private final ExcelExportService export;
    private final AuditService audit;
    private final Access access;

    @Operation(summary = "Prepare Excel export: notify the assigned Moderator about groups below 3; export stays allowed")
    @GetMapping("/export-check")
    public ExcelExportService.ExportCheck exportCheck(@PathVariable String semester,
            @RequestParam(required = false) UUID subjectId, @RequestParam(required = false) UUID majorId,
            @AuthenticationPrincipal User currentUser) {
        return export.check(semester, subjectId, majorId, currentUser);
    }

    public record AuditEntry(UUID id, String entityName, UUID entityId, String action, UUID performedBy,
                             String performedByName, Instant performedAt, String details) {
        static AuditEntry from(SystemAuditTrail a) {
            return new AuditEntry(a.getId(), a.getEntityName(), a.getEntityId(), a.getAction(),
                    a.getPerformedBy() == null ? null : a.getPerformedBy().getId(),
                    a.getPerformedBy() == null ? "Hệ thống" : a.getPerformedBy().getFullName(), a.getPerformedAt(),
                    a.getDetailsJson());
        }
    }

    @Operation(summary = "Statistics of the semester, a subject or one major (Admin, Moderator)")
    @GetMapping("/statistics")
    public StatisticsService.Statistics statistics(@PathVariable String semester,
                                                   @RequestParam(required = false) UUID subjectId,
                                                   @RequestParam(required = false) UUID majorId,
                                                   @AuthenticationPrincipal User currentUser) {
        return statistics.compute(semester, subjectId, majorId, currentUser);
    }

    @Operation(summary = "Export the handover list (Excel: Danh sách nhóm, Chưa có nhóm) by major, subject or semester",
            description = "Returns the .xlsx file itself, not JSON; errors are JSON.")
    @ApiResponse(responseCode = "200", description = "The Excel file",
            headers = @Header(name = "Content-Disposition",
                    description = "attachment; filename=\"Danh_sach_ghep_nhom_<HocKy>_<Nganh|TatCa>_<yyyyMMdd_HHmmss>.xlsx\""),
            content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable String semester,
                                         @RequestParam(required = false) UUID subjectId,
                                         @RequestParam(required = false) UUID majorId,
                                         @AuthenticationPrincipal User currentUser) {
        ExcelExportService.ExcelFile file = export.export(semester, subjectId, majorId, currentUser);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content());
    }


}
