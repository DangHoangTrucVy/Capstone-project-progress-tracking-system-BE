package com.capstone.tracking.subject;

import com.capstone.tracking.subject.dto.FinalizeCheck;
import com.capstone.tracking.subject.dto.ScopeResponse;
import com.capstone.tracking.subject.dto.SubjectResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Subjects & scopes", description = "Subjects needing groups (3.2) and their formation scopes: deadline, "
        + "extension, finalizing, reopening (3.4, 3.5, 7.2)")
@SecurityRequirement(name = "bearerAuth")
public class SubjectController {

    private final SubjectService subjects;
    private final ScopeService scopes;

    public record DeadlineRequest(Instant deadline) {}

    public record ExtendRequest(@NotNull Instant deadline) {}

    public record ReopenRequest(@NotBlank @Size(max = 1000) String reason) {}

    @Operation(summary = "List the semester's subjects with their scopes")
    @GetMapping("/semesters/{semester}/subjects")
    public List<SubjectResponse> list(@PathVariable String semester) {
        return subjects.list(semester);
    }

    @Operation(summary = "Create a subject (Moderator); a scope is created per major of the students in its term")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping("/semesters/{semester}/subjects")
    public ResponseEntity<SubjectResponse> create(@PathVariable String semester,
                                                  @RequestBody SubjectService.SubjectRequest request,
                                                  @AuthenticationPrincipal User currentUser) {
        Subject created = subjects.create(semester, request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/subjects/" + created.getId()))
                .body(subjects.toResponse(created));
    }

    @Operation(summary = "Get a subject with its scopes")
    @GetMapping("/subjects/{subjectId}")
    public SubjectResponse get(@PathVariable UUID subjectId) {
        return subjects.toResponse(subjects.get(subjectId));
    }

    @Operation(summary = "Rename a subject or change its request lifetime (Moderator)")
    @PutMapping("/subjects/{subjectId}")
    public SubjectResponse update(@PathVariable UUID subjectId, @RequestBody SubjectService.SubjectRequest request,
                                  @AuthenticationPrincipal User currentUser) {
        return subjects.toResponse(subjects.update(subjectId, request, currentUser));
    }

    @Operation(summary = "Delete a subject whose scopes have not opened yet (Moderator)")
    @DeleteMapping("/subjects/{subjectId}")
    public ResponseEntity<Void> delete(@PathVariable UUID subjectId, @AuthenticationPrincipal User currentUser) {
        subjects.delete(subjectId, currentUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get a formation scope")
    @GetMapping("/scopes/{scopeId}")
    public ScopeResponse scope(@PathVariable UUID scopeId) {
        return subjects.toResponse(scopes.get(scopeId));
    }

    @Operation(summary = "Set the scope's own deadline, or null to follow the common deadline (Moderator, before the deadline)")
    @PutMapping("/scopes/{scopeId}/deadline")
    public ScopeResponse setDeadline(@PathVariable UUID scopeId, @RequestBody DeadlineRequest request,
                                     @AuthenticationPrincipal User currentUser) {
        return subjects.toResponse(scopes.setDeadline(scopeId, request.deadline(), currentUser));
    }

    @Operation(summary = "Extend a scope past its deadline: open again with a new deadline (Moderator)")
    @PostMapping("/scopes/{scopeId}/extend")
    public ScopeResponse extend(@PathVariable UUID scopeId, @Valid @RequestBody ExtendRequest request,
                                @AuthenticationPrincipal User currentUser) {
        return subjects.toResponse(scopes.extend(scopeId, request.deadline(), currentUser));
    }

    @Operation(summary = "What still blocks finalizing the list, and the warnings")
    @GetMapping("/scopes/{scopeId}/finalize-check")
    public FinalizeCheck finalizeCheck(@PathVariable UUID scopeId) {
        return scopes.check(scopeId);
    }

    @Operation(summary = "Finalize the list for handover (Moderator)")
    @PostMapping("/scopes/{scopeId}/finalize")
    public ScopeResponse finalizeScope(@PathVariable UUID scopeId, @AuthenticationPrincipal User currentUser) {
        return subjects.toResponse(scopes.finalizeScope(scopeId, currentUser));
    }

    @Operation(summary = "Reopen a finalized list, with a reason (Admin or Moderator)")
    @PostMapping("/scopes/{scopeId}/reopen")
    public ScopeResponse reopen(@PathVariable UUID scopeId, @Valid @RequestBody ReopenRequest request,
                                @AuthenticationPrincipal User currentUser) {
        return subjects.toResponse(scopes.reopen(scopeId, request.reason(), currentUser));
    }
}
