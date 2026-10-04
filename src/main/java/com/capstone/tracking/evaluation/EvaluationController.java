package com.capstone.tracking.evaluation;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.evaluation.dto.EvaluationCreateRequest;
import com.capstone.tracking.evaluation.dto.EvaluationResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/** Sprint 5 — API-010: 3-criteria group evaluation, published to the group immediately on save. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Evaluations", description = "Instructor scoring of a group on 3 criteria")
@SecurityRequirement(name = "bearerAuth")
public class EvaluationController {

    private final EvaluationService evaluationService;

    @Operation(summary = "Score a group on the 3 criteria")
    @PostMapping("/api/v1/groups/{groupId}/evaluations")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<EvaluationResponse> create(@PathVariable UUID groupId,
                                                      @Valid @RequestBody EvaluationCreateRequest request,
                                                      @AuthenticationPrincipal User currentUser) {
        EvaluationRecord created = evaluationService.create(groupId, request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/evaluations/" + created.getId()))
                .body(EvaluationResponse.from(created));
    }

    @Operation(summary = "Evaluations of a group")
    @GetMapping("/api/v1/groups/{groupId}/evaluations")
    public Page<EvaluationResponse> listByGroup(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser,
                                                 @ParameterObject Pageable pageable) {
        return evaluationService.listByGroup(groupId, currentUser, pageable).map(EvaluationResponse::from);
    }

    @Operation(summary = "Get an evaluation")
    @GetMapping("/api/v1/evaluations/{id}")
    public EvaluationResponse getById(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return EvaluationResponse.from(evaluationService.getById(id, currentUser));
    }
}
