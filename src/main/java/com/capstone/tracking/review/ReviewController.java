package com.capstone.tracking.review;

import com.capstone.tracking.review.dto.ReviewCloneRequest;
import com.capstone.tracking.review.dto.ReviewResultRequest;
import com.capstone.tracking.review.dto.ReviewScheduleRequest;
import com.capstone.tracking.review.dto.ReviewSessionResponse;
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
@Tag(name = "Reviews", description = "Review 1, 2 and the closed council (Review 3)")
@SecurityRequirement(name = "bearerAuth")
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "Schedule a review session")
    @PostMapping("/api/v1/reviews")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReviewSessionResponse> schedule(@Valid @RequestBody ReviewScheduleRequest request,
                                                          @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.schedule(request, currentUser));
    }

    @Operation(summary = "Clone a round's schedule (e.g. Review 1 -> Review 2) shifted by offsetDays")
    @PostMapping("/api/v1/reviews/clone")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ReviewSessionResponse> cloneRound(@Valid @RequestBody ReviewCloneRequest request,
                                                  @AuthenticationPrincipal User currentUser) {
        return reviewService.cloneRound(request, currentUser);
    }

    @Operation(summary = "List review sessions of a round")
    @GetMapping("/api/v1/reviews")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','COUNCIL','ADMIN')")
    public List<ReviewSessionResponse> listByRound(@RequestParam ReviewRound round,
                                                   @RequestParam(required = false) String semester) {
        return reviewService.listByRound(round, semester);
    }

    @Operation(summary = "My review sessions")
    @GetMapping("/api/v1/reviews/mine")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','COUNCIL')")
    public List<ReviewSessionResponse> listMine(@AuthenticationPrincipal User currentUser) {
        return reviewService.listMine(currentUser);
    }

    @Operation(summary = "Get a review session")
    @GetMapping("/api/v1/reviews/{id}")
    public ReviewSessionResponse getById(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return reviewService.getById(id, currentUser);
    }

    @Operation(summary = "Review sessions of a group")
    @GetMapping("/api/v1/groups/{groupId}/reviews")
    public List<ReviewSessionResponse> listByGroup(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return reviewService.listByGroup(groupId, currentUser);
    }

    @Operation(summary = "Record the review result (Review 3: chair only, outcome required)")
    @PostMapping("/api/v1/reviews/{id}/result")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','COUNCIL','ADMIN')")
    public ReviewSessionResponse recordResult(@PathVariable UUID id, @Valid @RequestBody ReviewResultRequest request,
                                              @AuthenticationPrincipal User currentUser) {
        return reviewService.recordResult(id, request, currentUser);
    }

    @Operation(summary = "Confirm a REVISE_BEFORE_DEFENSE_1 group finished its revision")
    @PostMapping("/api/v1/reviews/{id}/revision-complete")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','COUNCIL','ADMIN')")
    public ReviewSessionResponse confirmRevision(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return reviewService.confirmRevision(id, currentUser);
    }
}
