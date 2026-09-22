package com.capstone.tracking.artifact;

import com.capstone.tracking.artifact.dto.ArtifactCreateRequest;
import com.capstone.tracking.artifact.dto.ArtifactResponse;
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

/** Sprint 3 — API-005: progress artifact submissions, scoped to a StudentGroup. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Artifact Submissions", description = "Group progress-document submissions")
@SecurityRequirement(name = "bearerAuth")
public class ArtifactController {

    private final ArtifactSubmissionService artifactSubmissionService;

    @PostMapping("/api/v1/groups/{groupId}/artifacts")
    @PreAuthorize("hasAnyRole('STUDENT','GROUP_LEADER')")
    public ResponseEntity<ArtifactResponse> create(@PathVariable UUID groupId,
                                                    @Valid @RequestBody ArtifactCreateRequest request,
                                                    @AuthenticationPrincipal User currentUser) {
        ArtifactSubmission created = artifactSubmissionService.create(groupId, request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/artifacts/" + created.getId()))
                .body(ArtifactResponse.from(created));
    }

    @GetMapping("/api/v1/groups/{groupId}/artifacts")
    public Page<ArtifactResponse> listByGroup(@PathVariable UUID groupId, Pageable pageable) {
        return artifactSubmissionService.listByGroup(groupId, pageable).map(ArtifactResponse::from);
    }

    @GetMapping("/api/v1/artifacts/{id}")
    public ArtifactResponse getById(@PathVariable UUID id) {
        return ArtifactResponse.from(artifactSubmissionService.getById(id));
    }

    @PostMapping("/api/v1/artifacts/{id}/accept")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public ArtifactResponse accept(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return ArtifactResponse.from(artifactSubmissionService.accept(id, currentUser));
    }
}
