package com.capstone.tracking.progress;

import com.capstone.tracking.progress.dto.ProgressFeedbackRequest;
import com.capstone.tracking.progress.dto.ProgressReportRequest;
import com.capstone.tracking.progress.dto.ProgressReportResponse;
import com.capstone.tracking.progress.dto.ProgressSummaryResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Weekly Progress", description = "Weekly progress reports and tasks for the group dashboard")
@SecurityRequirement(name = "bearerAuth")
public class ProgressController {

    private final ProgressService progressService;

    @Operation(summary = "Report a week's progress (one report per group and week)")
    @PostMapping("/api/v1/groups/{groupId}/progress")
    @PreAuthorize("hasAnyRole('STUDENT','GROUP_LEADER')")
    public ResponseEntity<ProgressReportResponse> create(@PathVariable UUID groupId,
                                                         @Valid @RequestBody ProgressReportRequest request,
                                                         @AuthenticationPrincipal User currentUser) {
        ProgressReportResponse created = progressService.create(groupId, request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/progress/" + created.id())).body(created);
    }

    @Operation(summary = "All weekly reports of a group, oldest week first")
    @GetMapping("/api/v1/groups/{groupId}/progress")
    public List<ProgressReportResponse> listByGroup(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return progressService.listByGroup(groupId, currentUser);
    }

    @Operation(summary = "Dashboard card: latest week and percentage trend")
    @GetMapping("/api/v1/groups/{groupId}/progress/summary")
    public ProgressSummaryResponse summary(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return progressService.summary(groupId, currentUser);
    }

    @GetMapping("/api/v1/progress/{id}")
    public ProgressReportResponse getById(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return progressService.getById(id, currentUser);
    }

    @Operation(summary = "Update a weekly report; tasks are replaced as a whole")
    @PutMapping("/api/v1/progress/{id}")
    @PreAuthorize("hasAnyRole('STUDENT','GROUP_LEADER')")
    public ProgressReportResponse update(@PathVariable UUID id, @Valid @RequestBody ProgressReportRequest request,
                                         @AuthenticationPrincipal User currentUser) {
        return progressService.update(id, request, currentUser);
    }

    @Operation(summary = "Supervisor feedback on a weekly report")
    @PutMapping("/api/v1/progress/{id}/feedback")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public ProgressReportResponse feedback(@PathVariable UUID id, @Valid @RequestBody ProgressFeedbackRequest request,
                                           @AuthenticationPrincipal User currentUser) {
        return progressService.giveFeedback(id, request, currentUser);
    }
}
