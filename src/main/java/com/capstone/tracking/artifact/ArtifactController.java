package com.capstone.tracking.artifact;

import com.capstone.tracking.artifact.ArtifactSubmissionService.DownloadableFile;
import com.capstone.tracking.artifact.dto.ArtifactCreateRequest;
import com.capstone.tracking.artifact.dto.ArtifactResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * API-005: group documents (reports, slides, source links...). Every group-scoped route is also exposed as
 * {@code /documents}, the name the frontend uses; both hit the same data.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Documents / Artifacts", description = "Group document submissions: uploaded files or links, per milestone")
@SecurityRequirement(name = "bearerAuth")
public class ArtifactController {

    private final ArtifactSubmissionService artifactSubmissionService;

    @Operation(summary = "Submit a link document (JSON)")
    @PostMapping(value = {"/api/v1/groups/{groupId}/artifacts", "/api/v1/groups/{groupId}/documents"},
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('STUDENT','GROUP_LEADER')")
    public ResponseEntity<ArtifactResponse> create(@PathVariable UUID groupId,
                                                    @Valid @RequestBody ArtifactCreateRequest request,
                                                    @AuthenticationPrincipal User currentUser) {
        return created(artifactSubmissionService.create(groupId, request, currentUser));
    }

    @Operation(summary = "Upload a file or submit a link (multipart/form-data: title, file | url, milestoneId?, sessionId?)")
    @PostMapping(value = {"/api/v1/groups/{groupId}/artifacts", "/api/v1/groups/{groupId}/documents"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('STUDENT','GROUP_LEADER')")
    public ResponseEntity<ArtifactResponse> upload(@PathVariable UUID groupId,
                                                    @RequestParam String title,
                                                    @RequestPart(required = false) MultipartFile file,
                                                    @RequestParam(required = false) String url,
                                                    @RequestParam(required = false) UUID milestoneId,
                                                    @RequestParam(required = false) UUID sessionId,
                                                    @AuthenticationPrincipal User currentUser) {
        return created(artifactSubmissionService.upload(groupId, title, sessionId, milestoneId, file, url, currentUser));
    }

    @GetMapping({"/api/v1/groups/{groupId}/artifacts", "/api/v1/groups/{groupId}/documents"})
    public Page<ArtifactResponse> listByGroup(@PathVariable UUID groupId,
                                              @RequestParam(required = false) UUID milestoneId,
                                              Pageable pageable) {
        return artifactSubmissionService.listByGroup(groupId, milestoneId, pageable).map(ArtifactResponse::from);
    }

    @GetMapping({"/api/v1/artifacts/{id}", "/api/v1/documents/{id}"})
    public ArtifactResponse getById(@PathVariable UUID id) {
        return ArtifactResponse.from(artifactSubmissionService.getById(id));
    }

    @Operation(summary = "Download the uploaded file of a FILE document")
    @GetMapping({"/api/v1/artifacts/{id}/file", "/api/v1/documents/{id}/file"})
    public ResponseEntity<Resource> download(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        DownloadableFile file = artifactSubmissionService.loadFile(id, currentUser);
        MediaType type = file.contentType() != null ? MediaType.parseMediaType(file.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }

    @PostMapping({"/api/v1/artifacts/{id}/accept", "/api/v1/documents/{id}/accept"})
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public ArtifactResponse accept(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return ArtifactResponse.from(artifactSubmissionService.accept(id, currentUser));
    }

    private ResponseEntity<ArtifactResponse> created(ArtifactSubmission artifact) {
        return ResponseEntity.created(URI.create("/api/v1/documents/" + artifact.getId()))
                .body(ArtifactResponse.from(artifact));
    }
}
