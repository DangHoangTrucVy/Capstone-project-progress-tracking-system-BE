package com.capstone.tracking.semester;

import com.capstone.tracking.semester.SemesterService.SemesterRequest;
import com.capstone.tracking.semester.SemesterService.SemesterResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/semesters")
@RequiredArgsConstructor
@Tag(name = "Semesters", description = "The central entity: semester, Moderator, rules, deadline and the four phases "
        + "(Mở học kỳ, Lập nhóm, Bổ sung, Kết thúc ghép nhóm)")
@SecurityRequirement(name = "bearerAuth")
public class SemesterController {

    private final SemesterService service;
    private final PhaseService phases;

    public record ModeratorRequest(@com.fasterxml.jackson.annotation.JsonAlias("lecturerId") UUID moderatorId) {}

    public record DeadlineRequest(@NotNull Instant deadline) {}

    public record Round1Request(boolean skipSupplement) {}

    @Operation(summary = "List semesters; search code/name, filter phase or my assignment, sort by "
            + "code/startDate/phase/studentCount/roomCount")
    @GetMapping
    public List<SemesterResponse> list(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) SemesterPhase phase,
                                       @RequestParam(defaultValue = "false") boolean moderatedByMe,
                                       @RequestParam(defaultValue = "startDate") String sort,
                                       @RequestParam(defaultValue = "desc") String direction,
                                       @AuthenticationPrincipal User currentUser) {
        return service.list(q, phase, moderatedByMe, currentUser, sort, "desc".equalsIgnoreCase(direction));
    }

    @Operation(summary = "Create a semester (Admin); it starts in phase OPENING")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping
    public ResponseEntity<SemesterResponse> create(@RequestBody SemesterRequest request,
                                                   @AuthenticationPrincipal User currentUser) {
        Semester created = service.create(request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/semesters/" + created.getCode()))
                .body(service.toResponse(created));
    }

    @Operation(summary = "Get a semester: phase, timeline, rules, Moderator and counts")
    @GetMapping("/{semester}")
    public SemesterResponse get(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        return service.details(semester, currentUser);
    }

    @Operation(summary = "Update a semester: Admin edits name and dates, Admin or Moderator the opening date")
    @PutMapping("/{semester}")
    public SemesterResponse update(@PathVariable String semester, @RequestBody SemesterRequest request,
                                   @AuthenticationPrincipal User currentUser) {
        return service.toResponse(service.update(semester, request, currentUser));
    }

    @Operation(summary = "Delete an empty semester with no students and no rooms (Admin)")
    @DeleteMapping("/{semester}")
    public ResponseEntity<Void> delete(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        service.delete(semester, currentUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Assign or replace the semester's Moderator; null removes it (Admin)")
    @PutMapping("/{semester}/moderator")
    public SemesterResponse assignModerator(@PathVariable String semester, @RequestBody ModeratorRequest request,
                                            @AuthenticationPrincipal User currentUser) {
        return service.toResponse(service.assignModerator(semester, request.moderatorId(), currentUser));
    }

    // ------------------------------------------------------------------ rules and timeline

    @Operation(summary = "The semester's rules: max group size, default min room size, pending requests, request lifetime")
    @GetMapping("/{semester}/rules")
    public SemesterService.Rules rules(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        return service.rules(semester, currentUser);
    }

    @Operation(summary = "Change the rules (Moderator): max size free in OPENING, only up afterwards; min before round 1")
    @PutMapping("/{semester}/rules")
    public SemesterService.Rules updateRules(@PathVariable String semester,
                                             @RequestBody SemesterService.RulesRequest request,
                                             @AuthenticationPrincipal User currentUser) {
        return service.rules(service.updateRules(semester, request, currentUser));
    }

    @Operation(summary = "Set or extend the common deadline (Moderator, before round 1, at least the minimum notice "
            + "from now); locked rooms open again until the new deadline")
    @PutMapping("/{semester}/deadline")
    public SemesterResponse setDeadline(@PathVariable String semester, @Valid @RequestBody DeadlineRequest request,
                                        @AuthenticationPrincipal User currentUser) {
        return service.toResponse(phases.setDeadline(semester, request.deadline(), currentUser));
    }

    // ------------------------------------------------------------------ phases

    @Operation(summary = "What blocks the next transition (start formation, round 1, round 2) and the warnings")
    @GetMapping("/{semester}/phase-check")
    public PhaseService.PhaseCheck phaseCheck(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        return phases.check(semester, currentUser);
    }

    @Operation(summary = "Mở học kỳ -> Lập nhóm (Moderator): needs students, a deadline and a room in every major")
    @PostMapping("/{semester}/start-formation")
    public SemesterResponse startFormation(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        return service.toResponse(phases.startForming(semester, currentUser));
    }

    @Operation(summary = "Chốt đợt 1 (Moderator, after the deadline): to Bổ sung, or skipSupplement=true straight to "
            + "Kết thúc when no student is left without a group")
    @PostMapping("/{semester}/finalize-round1")
    public SemesterResponse finalizeRound1(@PathVariable String semester,
                                           @RequestBody(required = false) Round1Request request,
                                           @AuthenticationPrincipal User currentUser) {
        return service.toResponse(phases.finalizeRound1(semester, request != null && request.skipSupplement(),
                currentUser));
    }

    @Operation(summary = "Chốt đợt 2 (Moderator, in Bổ sung): close invites and end formation; remaining rooms become "
            + "coded groups, empty rooms close")
    @PostMapping("/{semester}/finalize-round2")
    public SemesterResponse finalizeRound2(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        return service.toResponse(phases.finalizeRound2(semester, currentUser));
    }
}
