package com.capstone.tracking.meeting;

import com.capstone.tracking.meeting.dto.RequirementCreateRequest;
import com.capstone.tracking.meeting.dto.RequirementResponse;
import com.capstone.tracking.meeting.dto.RequirementUpdateRequest;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/** Sprint 4 — API-007: requirements logged against a meeting session. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Requirement Logs", description = "Requirements raised during a meeting session")
@SecurityRequirement(name = "bearerAuth")
public class RequirementLogController {

    private final RequirementLogService requirementLogService;

    @PostMapping("/api/v1/meetings/{id}/requirements")
    @PreAuthorize("hasAnyRole('GROUP_LEADER','INSTRUCTOR')")
    public ResponseEntity<RequirementResponse> create(@PathVariable UUID id,
                                                       @Valid @RequestBody RequirementCreateRequest request,
                                                       @AuthenticationPrincipal User currentUser) {
        RequirementLog created = requirementLogService.create(id, request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/requirements/" + created.getId()))
                .body(RequirementResponse.from(created));
    }

    @GetMapping("/api/v1/meetings/{id}/requirements")
    public Page<RequirementResponse> listBySession(@PathVariable UUID id, Pageable pageable) {
        return requirementLogService.listBySession(id, pageable).map(RequirementResponse::from);
    }

    @PutMapping("/api/v1/requirements/{id}")
    @PreAuthorize("hasAnyRole('GROUP_LEADER','INSTRUCTOR')")
    public RequirementResponse update(@PathVariable UUID id, @RequestBody RequirementUpdateRequest request,
                                       @AuthenticationPrincipal User currentUser) {
        return RequirementResponse.from(requirementLogService.update(id, request, currentUser));
    }
}
