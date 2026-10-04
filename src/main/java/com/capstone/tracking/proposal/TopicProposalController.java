package com.capstone.tracking.proposal;

import com.capstone.tracking.proposal.dto.ProposalDecisionRequest;
import com.capstone.tracking.proposal.dto.ProposalForwardRequest;
import com.capstone.tracking.proposal.dto.ProposalRoundRequest;
import com.capstone.tracking.proposal.dto.ProposalRoundResponse;
import com.capstone.tracking.proposal.dto.ProposalSubmitRequest;
import com.capstone.tracking.proposal.dto.TopicProposalResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Giai đoạn 2: topic registration, supervisor pre-review, Council approval (max 4 rounds). */
@RestController
@RequiredArgsConstructor
@Tag(name = "Topic Proposals", description = "Leader submits topics, Instructor forwards one, Council approves/rejects")
@SecurityRequirement(name = "bearerAuth")
public class TopicProposalController {

    private final TopicProposalService proposalService;

    @Operation(summary = "Leader submits the topic list (10 topics in round 1)")
    @PostMapping("/api/v1/groups/{groupId}/topic-proposals")
    @PreAuthorize("hasRole('GROUP_LEADER')")
    public ResponseEntity<TopicProposalResponse> submit(@PathVariable UUID groupId,
                                                        @Valid @RequestBody ProposalSubmitRequest request,
                                                        @AuthenticationPrincipal User currentUser) {
        TopicProposalResponse created = proposalService.submit(groupId, request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/topic-proposals/" + created.id())).body(created);
    }

    @Operation(summary = "Topic proposals of a group")
    @GetMapping("/api/v1/groups/{groupId}/topic-proposals")
    public List<TopicProposalResponse> listByGroup(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return proposalService.listByGroup(groupId, currentUser);
    }

    @Operation(summary = "Review queue", description = "Instructor: supervised groups only. Council/Admin: all groups.")
    @GetMapping("/api/v1/topic-proposals")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','COUNCIL','ADMIN')")
    public Page<TopicProposalResponse> queue(@RequestParam(required = false) ProposalStatus status,
                                             @AuthenticationPrincipal User currentUser, @ParameterObject Pageable pageable) {
        return proposalService.queue(status, currentUser, pageable);
    }

    @Operation(summary = "Get a topic proposal")
    @GetMapping("/api/v1/topic-proposals/{id}")
    public TopicProposalResponse getById(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return proposalService.getById(id, currentUser);
    }

    @Operation(summary = "Supervisor picks one topic and forwards it to the Council")
    @PostMapping("/api/v1/topic-proposals/{id}/forward")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public TopicProposalResponse forward(@PathVariable UUID id, @Valid @RequestBody ProposalForwardRequest request,
                                         @AuthenticationPrincipal User currentUser) {
        return proposalService.forward(id, request, currentUser);
    }

    @Operation(summary = "Council approves or rejects the forwarded topic")
    @PostMapping("/api/v1/topic-proposals/{id}/decision")
    @PreAuthorize("hasAnyRole('COUNCIL','ADMIN')")
    public TopicProposalResponse decide(@PathVariable UUID id, @Valid @RequestBody ProposalDecisionRequest request,
                                        @AuthenticationPrincipal User currentUser) {
        return proposalService.decide(id, request, currentUser);
    }

    @Operation(summary = "Admin opens (or re-opens) the submission window for round 2, 3 or 4")
    @PostMapping("/api/v1/proposal-rounds")
    @PreAuthorize("hasRole('ADMIN')")
    public ProposalRoundResponse openRound(@Valid @RequestBody ProposalRoundRequest request,
                                           @AuthenticationPrincipal User currentUser) {
        return proposalService.openRound(request, currentUser);
    }

    @Operation(summary = "Close a proposal round")
    @PutMapping("/api/v1/proposal-rounds/{id}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public ProposalRoundResponse closeRound(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return proposalService.closeRound(id, currentUser);
    }

    @Operation(summary = "List proposal rounds")
    @GetMapping("/api/v1/proposal-rounds")
    public List<ProposalRoundResponse> listRounds(@RequestParam(required = false) String semester) {
        return proposalService.listRounds(semester);
    }
}
