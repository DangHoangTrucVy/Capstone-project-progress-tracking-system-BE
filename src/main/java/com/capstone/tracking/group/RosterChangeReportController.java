package com.capstone.tracking.group;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** YC19/YC20: supervisor -> Admin reports about a group's roster or leader. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Group Formation")
@SecurityRequirement(name = "bearerAuth")
public class RosterChangeReportController {

    private final RosterChangeReportService service;

    public record ReportRequest(@NotNull RosterChangeReport.Type type, @NotBlank @Size(max = 1000) String description) {}

    public record ResolveRequest(@Size(max = 1000) String note) {}

    public record ReportResponse(UUID id, UUID groupId, String groupCode, RosterChangeReport.Type type,
                                 String description, RosterChangeReport.Status status, String reportedBy,
                                 Instant createdAt, Instant resolvedAt, String resolutionNote) {
        static ReportResponse from(RosterChangeReport r) {
            return new ReportResponse(r.getId(), r.getGroup().getId(), r.getGroup().getGroupCode(), r.getType(),
                    r.getDescription(), r.getStatus(), r.getReportedBy().getFullName(), r.getCreatedAt(),
                    r.getResolvedAt(), r.getResolutionNote());
        }
    }

    @Operation(summary = "Report a roster change to the Admin")
    @PostMapping("/groups/{groupId}/roster-change-reports")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ReportResponse> report(@PathVariable UUID groupId, @Valid @RequestBody ReportRequest request,
                                                 @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportResponse.from(
                service.report(groupId, currentUser, request.type(), request.description())));
    }

    @Operation(summary = "Roster change reports of a group")
    @GetMapping("/groups/{groupId}/roster-change-reports")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public List<ReportResponse> forGroup(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return service.listForGroup(groupId, currentUser).stream().map(ReportResponse::from).toList();
    }

    @Operation(summary = "All roster change reports (filter by status)")
    @GetMapping("/roster-change-reports")
    @PreAuthorize("hasRole('ADMIN')")
    public Page<ReportResponse> list(@RequestParam(required = false) RosterChangeReport.Status status, @ParameterObject Pageable pageable) {
        return service.list(status, pageable).map(ReportResponse::from);
    }

    @Operation(summary = "Resolve a roster change report")
    @PostMapping("/roster-change-reports/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ReportResponse resolve(@PathVariable UUID id, @Valid @RequestBody(required = false) ResolveRequest request,
                                  @AuthenticationPrincipal User currentUser) {
        return ReportResponse.from(service.resolve(id, currentUser, request == null ? null : request.note()));
    }
}
