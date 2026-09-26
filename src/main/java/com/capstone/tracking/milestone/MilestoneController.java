package com.capstone.tracking.milestone;

import com.capstone.tracking.milestone.dto.MilestoneCreateRequest;
import com.capstone.tracking.milestone.dto.MilestoneResponse;
import com.capstone.tracking.user.User;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/milestones")
@RequiredArgsConstructor
@Tag(name = "Milestones", description = "Semester checkpoints that group documents are submitted against")
@SecurityRequirement(name = "bearerAuth")
public class MilestoneController {

    private final MilestoneService milestoneService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MilestoneResponse> create(@Valid @RequestBody MilestoneCreateRequest request,
                                                     @AuthenticationPrincipal User currentUser) {
        Milestone created = milestoneService.create(request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/milestones/" + created.getId()))
                .body(MilestoneResponse.from(created));
    }

    /** Ordered by sequenceNo; a semester has only a handful of milestones, so no paging. */
    @GetMapping
    public List<MilestoneResponse> list(@RequestParam(required = false) String semester) {
        return milestoneService.list(semester).stream().map(MilestoneResponse::from).toList();
    }

    @GetMapping("/{id}")
    public MilestoneResponse getById(@PathVariable UUID id) {
        return MilestoneResponse.from(milestoneService.getById(id));
    }
}
