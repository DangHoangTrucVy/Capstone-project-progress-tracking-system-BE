package com.capstone.tracking.defense;

import com.capstone.tracking.defense.dto.DefenseResultRequest;
import com.capstone.tracking.defense.dto.DefenseScheduleRequest;
import com.capstone.tracking.defense.dto.DefenseSessionResponse;
import com.capstone.tracking.defense.dto.RollingScheduleRequest;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Defenses", description = "Final defense scheduling (rolling) and grading, attempts 1 and 2")
@SecurityRequirement(name = "bearerAuth")
public class DefenseController {

    private final DefenseService defenseService;

    @Operation(summary = "Schedule a defense")
    @PostMapping("/api/v1/defenses")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DefenseSessionResponse> schedule(@Valid @RequestBody DefenseScheduleRequest request,
                                                           @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(defenseService.schedule(request, currentUser));
    }

    @Operation(summary = "Schedule groups back to back in one room with one committee (cuốn chiếu)")
    @PostMapping("/api/v1/defenses/rolling")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<DefenseSessionResponse>> scheduleRolling(@Valid @RequestBody RollingScheduleRequest request,
                                                                        @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(defenseService.scheduleRolling(request, currentUser));
    }

    @Operation(summary = "List defenses (filter by attempt)")
    @GetMapping("/api/v1/defenses")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','COUNCIL','ADMIN')")
    public List<DefenseSessionResponse> listByAttempt(@RequestParam(defaultValue = "1") int attempt,
                                                      @RequestParam(required = false) String semester) {
        return defenseService.listByAttempt(attempt, semester);
    }

    @Operation(summary = "My defenses")
    @GetMapping("/api/v1/defenses/mine")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','COUNCIL')")
    public List<DefenseSessionResponse> listMine(@AuthenticationPrincipal User currentUser) {
        return defenseService.listMine(currentUser);
    }

    @Operation(summary = "Get a defense")
    @GetMapping("/api/v1/defenses/{id}")
    public DefenseSessionResponse getById(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return defenseService.getById(id, currentUser);
    }

    @Operation(summary = "Defenses of a group")
    @GetMapping("/api/v1/groups/{groupId}/defenses")
    public List<DefenseSessionResponse> listByGroup(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return defenseService.listByGroup(groupId, currentUser);
    }

    @Operation(summary = "Committee chair records pass/fail and score")
    @PostMapping("/api/v1/defenses/{id}/result")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','COUNCIL','ADMIN')")
    public DefenseSessionResponse recordResult(@PathVariable UUID id, @Valid @RequestBody DefenseResultRequest request,
                                               @AuthenticationPrincipal User currentUser) {
        return defenseService.recordResult(id, request, currentUser);
    }
}
