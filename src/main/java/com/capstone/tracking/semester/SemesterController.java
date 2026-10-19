package com.capstone.tracking.semester;

import com.capstone.tracking.semester.SemesterService.SemesterRequest;
import com.capstone.tracking.semester.SemesterService.SemesterResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/semesters")
@RequiredArgsConstructor
@Tag(name = "Semesters", description = "Semester list, common timeline, archiving and Moderator assignment (3.1, 3.6)")
@SecurityRequirement(name = "bearerAuth")
public class SemesterController {

    private final SemesterService service;

    public record ModeratorRequest(UUID lecturerId) {}

    @Operation(summary = "List semesters (status filter; archived only when asked; sort by code, startDate, status, "
            + "studentCount or groupCount)")
    @GetMapping
    public List<SemesterResponse> list(@RequestParam(required = false) SemesterStatus status,
                                       @RequestParam(defaultValue = "false") boolean moderatedByMe,
                                       @RequestParam(defaultValue = "startDate") String sort,
                                       @RequestParam(defaultValue = "desc") String direction,
                                       @AuthenticationPrincipal User currentUser) {
        return service.list(status, moderatedByMe, currentUser, sort, "desc".equalsIgnoreCase(direction));
    }

    @Operation(summary = "Create a semester (Admin)")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping
    public ResponseEntity<SemesterResponse> create(@RequestBody SemesterRequest request,
                                                   @AuthenticationPrincipal User currentUser) {
        Semester created = service.create(request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/semesters/" + created.getCode()))
                .body(service.toResponse(created));
    }

    @Operation(summary = "Get a semester and its status")
    @GetMapping("/{semester}")
    public SemesterResponse get(@PathVariable String semester) {
        return service.toResponse(service.get(semester));
    }

    @Operation(summary = "Update a semester: Admin edits everything, the Moderator the opening date and common deadline")
    @PutMapping("/{semester}")
    public SemesterResponse update(@PathVariable String semester, @RequestBody SemesterRequest request,
                                   @AuthenticationPrincipal User currentUser) {
        return service.toResponse(service.update(semester, request, currentUser));
    }

    @Operation(summary = "Archive a semester: hidden from the active list, still viewable (Admin)")
    @PostMapping("/{semester}/archive")
    public SemesterResponse archive(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        return service.toResponse(service.setArchived(semester, true, currentUser));
    }

    @Operation(summary = "Restore an archived semester (Admin)")
    @PostMapping("/{semester}/unarchive")
    public SemesterResponse unarchive(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        return service.toResponse(service.setArchived(semester, false, currentUser));
    }

    @Operation(summary = "Delete an empty semester (no students, no subjects) (Admin)")
    @DeleteMapping("/{semester}")
    public ResponseEntity<Void> delete(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        service.delete(semester, currentUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Assign or replace the semester's Moderator; null removes it (Admin)")
    @PutMapping("/{semester}/moderator")
    public SemesterResponse assignModerator(@PathVariable String semester, @RequestBody ModeratorRequest request,
                                            @AuthenticationPrincipal User currentUser) {
        return service.toResponse(service.assignModerator(semester, request.lecturerId(), currentUser));
    }
}
