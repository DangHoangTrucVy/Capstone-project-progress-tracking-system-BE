package com.capstone.tracking.group;

import com.capstone.tracking.group.dto.ApplyRequest;
import com.capstone.tracking.group.dto.GroupMemberResponse;
import com.capstone.tracking.group.dto.InviteRequest;
import com.capstone.tracking.group.dto.JoinRequestResponse;
import com.capstone.tracking.group.dto.LeaveRequestBody;
import com.capstone.tracking.group.dto.LeaveRequestResponse;
import com.capstone.tracking.group.dto.VoteRequest;
import com.capstone.tracking.group.dto.VoteResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Group formation endpoints: Apply / Invite (YC08-YC15), advisory votes (YC12), leave requests (YC17) and the
 * per-semester request lifetime (YC14). Students reach them even before they belong to a group.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Group Formation", description = "Apply, Invite, votes and leave requests")
@SecurityRequirement(name = "bearerAuth")
public class GroupJoinController {

    private static final String STUDENT = "hasAnyRole('STUDENT','GROUP_LEADER')";
    private static final String LEADER = "hasRole('GROUP_LEADER')";

    private final GroupJoinService joinService;
    private final GroupLeaveService leaveService;

    public record TtlRequest(@NotNull Integer ttlHours) {}

    public record TtlResponse(String semester, int ttlHours) {}

    // ------------------------------------------------------------------ Apply (student side)

    @PostMapping("/groups/{groupId}/applications")
    @PreAuthorize(STUDENT)
    public ResponseEntity<JoinRequestResponse> apply(@PathVariable UUID groupId,
                                                     @Valid @RequestBody(required = false) ApplyRequest request,
                                                     @AuthenticationPrincipal User currentUser) {
        GroupJoinRequest created = joinService.apply(groupId, currentUser, request == null ? null : request.message());
        return ResponseEntity.created(URI.create("/api/v1/applications/" + created.getId()))
                .body(joinService.toResponse(created, currentUser));
    }

    @PostMapping("/applications/{id}/withdraw")
    @PreAuthorize(STUDENT)
    public JoinRequestResponse withdraw(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.toResponse(joinService.withdrawApplication(id, currentUser), currentUser);
    }

    @GetMapping("/me/applications")
    public List<JoinRequestResponse> myApplications(@AuthenticationPrincipal User currentUser) {
        return joinService.listMine(JoinRequestType.APPLY, currentUser);
    }

    @GetMapping("/me/invites")
    public List<JoinRequestResponse> myInvites(@AuthenticationPrincipal User currentUser) {
        return joinService.listMine(JoinRequestType.INVITE, currentUser);
    }

    // ------------------------------------------------------------------ Apply (group side)

    @GetMapping("/groups/{groupId}/applications")
    public List<JoinRequestResponse> applications(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return joinService.listForGroup(groupId, JoinRequestType.APPLY, currentUser);
    }

    /** Approving an Apply sends the applicant an Invite; they join only when they Accept it (YC10). */
    @PostMapping("/applications/{id}/approve")
    @PreAuthorize(LEADER)
    public JoinRequestResponse approve(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.toResponse(joinService.approveApplication(id, currentUser), currentUser);
    }

    @PostMapping("/applications/{id}/reject")
    @PreAuthorize(LEADER)
    public JoinRequestResponse reject(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.toResponse(joinService.rejectApplication(id, currentUser), currentUser);
    }

    @PostMapping("/applications/{id}/votes")
    public VoteResponse vote(@PathVariable UUID id, @Valid @RequestBody VoteRequest request,
                             @AuthenticationPrincipal User currentUser) {
        return VoteResponse.from(joinService.vote(id, currentUser, request.vote(), request.comment()));
    }

    @GetMapping("/applications/{id}/votes")
    public List<VoteResponse> votes(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.listVotes(id, currentUser).stream().map(VoteResponse::from).toList();
    }

    // ------------------------------------------------------------------ Invite

    @PostMapping("/groups/{groupId}/invites")
    @PreAuthorize(LEADER)
    public ResponseEntity<JoinRequestResponse> invite(@PathVariable UUID groupId,
                                                      @Valid @RequestBody InviteRequest request,
                                                      @AuthenticationPrincipal User currentUser) {
        GroupJoinRequest created = joinService.sendInvite(groupId, currentUser, request.userId(), request.email(),
                request.identifier(), request.message());
        return ResponseEntity.created(URI.create("/api/v1/invites/" + created.getId()))
                .body(joinService.toResponse(created, currentUser));
    }

    @GetMapping("/groups/{groupId}/invites")
    public List<JoinRequestResponse> invites(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return joinService.listForGroup(groupId, JoinRequestType.INVITE, currentUser);
    }

    /** Accepting is the moment of joining: the student becomes an official member. */
    @PostMapping("/invites/{id}/accept")
    @PreAuthorize(STUDENT)
    public ResponseEntity<GroupMemberResponse> accept(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GroupMemberResponse.from(joinService.acceptInvite(id, currentUser)));
    }

    @PostMapping("/invites/{id}/decline")
    @PreAuthorize(STUDENT)
    public JoinRequestResponse decline(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.toResponse(joinService.declineInvite(id, currentUser), currentUser);
    }

    @PostMapping("/invites/{id}/revoke")
    @PreAuthorize(LEADER)
    public JoinRequestResponse revoke(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.toResponse(joinService.revokeInvite(id, currentUser), currentUser);
    }

    // ------------------------------------------------------------------ leaving

    @PostMapping("/groups/{groupId}/leave-requests")
    @PreAuthorize(STUDENT)
    public ResponseEntity<LeaveRequestResponse> requestLeave(@PathVariable UUID groupId,
                                                             @Valid @RequestBody(required = false) LeaveRequestBody body,
                                                             @AuthenticationPrincipal User currentUser) {
        MemberLeaveRequest created = leaveService.request(groupId, currentUser, body == null ? null : body.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(LeaveRequestResponse.from(created));
    }

    @GetMapping("/groups/{groupId}/leave-requests")
    public List<LeaveRequestResponse> leaveRequests(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return leaveService.list(groupId, currentUser).stream().map(LeaveRequestResponse::from).toList();
    }

    @PostMapping("/leave-requests/{id}/approve")
    @PreAuthorize(LEADER)
    public LeaveRequestResponse approveLeave(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return LeaveRequestResponse.from(leaveService.approve(id, currentUser));
    }

    @PostMapping("/leave-requests/{id}/reject")
    @PreAuthorize(LEADER)
    public LeaveRequestResponse rejectLeave(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return LeaveRequestResponse.from(leaveService.reject(id, currentUser));
    }

    @PostMapping("/leave-requests/{id}/withdraw")
    @PreAuthorize(STUDENT)
    public LeaveRequestResponse withdrawLeave(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return LeaveRequestResponse.from(leaveService.withdraw(id, currentUser));
    }

    // ------------------------------------------------------------------ settings

    /** YC14: how long an unanswered Apply/Invite stays open in this semester (default 48 hours). */
    @GetMapping("/semesters/{semester}/join-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public TtlResponse getTtl(@PathVariable String semester) {
        return new TtlResponse(semester, joinService.ttlHours(semester));
    }

    @PutMapping("/semesters/{semester}/join-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public TtlResponse setTtl(@PathVariable String semester, @Valid @RequestBody TtlRequest request) {
        return new TtlResponse(semester, joinService.setTtlHours(semester, request.ttlHours()));
    }
}
