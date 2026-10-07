package com.capstone.tracking.group;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.group.dto.ApplyRequest;
import com.capstone.tracking.group.dto.GroupMemberResponse;
import com.capstone.tracking.group.dto.InviteRequest;
import com.capstone.tracking.group.dto.JoinRequestResponse;
import com.capstone.tracking.group.dto.LeaveRequestBody;
import com.capstone.tracking.group.dto.LeaveRequestResponse;
import com.capstone.tracking.group.dto.RejectApplicationRequest;
import com.capstone.tracking.group.dto.VoteRequest;
import com.capstone.tracking.group.dto.VoteResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
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
    private final FormationWindow formationWindow;

    public record TtlRequest(@NotNull Integer ttlHours) {}

    /** @param formationDeadline end of the permitted period for students' own roster changes; null = none */
    public record TtlResponse(String semester, int ttlHours, Instant formationDeadline) {}

    /** @param deadline null removes the cut-off */
    public record DeadlineRequest(Instant deadline) {}

    /** What a student needs to know about the semester's formation rules. */
    public record FormationWindowResponse(String semester, int requestTtlHours, Instant formationDeadline, boolean open,
                                          int maxOpenApplications, int minMembers, int maxMembers) {}

    // ------------------------------------------------------------------ Apply (student side)

    @Operation(summary = "Apply to join a group")
    @PostMapping("/groups/{groupId}/applications")
    @PreAuthorize(STUDENT)
    public ResponseEntity<JoinRequestResponse> apply(@PathVariable UUID groupId,
                                                     @Valid @RequestBody(required = false) ApplyRequest request,
                                                     @AuthenticationPrincipal User currentUser) {
        GroupJoinRequest created = joinService.apply(groupId, currentUser, request == null ? null : request.message());
        return ResponseEntity.created(URI.create("/api/v1/applications/" + created.getId()))
                .body(joinService.toResponse(created, currentUser));
    }

    @Operation(summary = "Withdraw my application")
    @PostMapping("/applications/{id}/withdraw")
    @PreAuthorize(STUDENT)
    public JoinRequestResponse withdraw(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.toResponse(joinService.withdrawApplication(id, currentUser), currentUser);
    }

    @Operation(summary = "My applications")
    @GetMapping("/me/applications")
    public List<JoinRequestResponse> myApplications(@AuthenticationPrincipal User currentUser) {
        return joinService.listMine(JoinRequestType.APPLY, currentUser);
    }

    @Operation(summary = "My pending invites")
    @GetMapping("/me/invites")
    public List<JoinRequestResponse> myInvites(@AuthenticationPrincipal User currentUser) {
        return joinService.listMine(JoinRequestType.INVITE, currentUser);
    }

    // ------------------------------------------------------------------ Apply (group side)

    @Operation(summary = "Applications to a group")
    @GetMapping("/groups/{groupId}/applications")
    public List<JoinRequestResponse> applications(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return joinService.listForGroup(groupId, JoinRequestType.APPLY, currentUser);
    }

    /** Approving an Apply directly makes the applicant an active group member. */
    @Operation(summary = "Approve an application")
    @PostMapping("/applications/{id}/approve")
    @PreAuthorize(LEADER)
    public JoinRequestResponse approve(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.toResponse(joinService.approveApplication(id, currentUser), currentUser);
    }

    /** The optional reason is stored on the application and sent to the applicant with the notification. */
    @Operation(summary = "Reject an application (optional reason forwarded to the student)")
    @PostMapping("/applications/{id}/reject")
    @PreAuthorize(LEADER)
    public JoinRequestResponse reject(@PathVariable UUID id,
                                      @Valid @RequestBody(required = false) RejectApplicationRequest request,
                                      @AuthenticationPrincipal User currentUser) {
        return joinService.toResponse(joinService.rejectApplication(id, currentUser,
                request == null ? null : request.reason()), currentUser);
    }

    @Operation(summary = "Member votes on an application")
    @PostMapping("/applications/{id}/votes")
    public VoteResponse vote(@PathVariable UUID id, @Valid @RequestBody VoteRequest request,
                             @AuthenticationPrincipal User currentUser) {
        return VoteResponse.from(joinService.vote(id, currentUser, request.vote(), request.comment()));
    }

    @Operation(summary = "Votes on an application")
    @GetMapping("/applications/{id}/votes")
    public List<VoteResponse> votes(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.listVotes(id, currentUser).stream().map(VoteResponse::from).toList();
    }

    // ------------------------------------------------------------------ Invite

    @Operation(summary = "Invite a student to the group")
    @PostMapping("/groups/{groupId}/invites")
    @PreAuthorize(LEADER)
    public ResponseEntity<JoinRequestResponse> invite(@PathVariable UUID groupId,
                                                      @Valid @RequestBody InviteRequest request,
                                                      @AuthenticationPrincipal User currentUser) {
        GroupJoinRequest created = joinService.sendInvite(groupId, currentUser, request.userId(), request.email(),
                request.identifier(), request.studentCode(), request.message());
        return ResponseEntity.created(URI.create("/api/v1/invites/" + created.getId()))
                .body(joinService.toResponse(created, currentUser));
    }

    @Operation(summary = "Invites sent by a group")
    @GetMapping("/groups/{groupId}/invites")
    public List<JoinRequestResponse> invites(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return joinService.listForGroup(groupId, JoinRequestType.INVITE, currentUser);
    }

    /** Accepting is the moment of joining: the student becomes an official member. */
    @Operation(summary = "Accept an invite")
    @PostMapping("/invites/{id}/accept")
    @PreAuthorize(STUDENT)
    public ResponseEntity<GroupMemberResponse> accept(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GroupMemberResponse.from(joinService.acceptInvite(id, currentUser)));
    }

    @Operation(summary = "Decline an invite")
    @PostMapping("/invites/{id}/decline")
    @PreAuthorize(STUDENT)
    public JoinRequestResponse decline(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.toResponse(joinService.declineInvite(id, currentUser), currentUser);
    }

    @Operation(summary = "Revoke an invite")
    @PostMapping("/invites/{id}/revoke")
    @PreAuthorize(LEADER)
    public JoinRequestResponse revoke(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return joinService.toResponse(joinService.revokeInvite(id, currentUser), currentUser);
    }

    // ------------------------------------------------------------------ leaving

    @Operation(summary = "Request to leave the group")
    @PostMapping("/groups/{groupId}/leave-requests")
    @PreAuthorize(STUDENT)
    public ResponseEntity<LeaveRequestResponse> requestLeave(@PathVariable UUID groupId,
                                                             @Valid @RequestBody(required = false) LeaveRequestBody body,
                                                             @AuthenticationPrincipal User currentUser) {
        MemberLeaveRequest created = leaveService.request(groupId, currentUser, body == null ? null : body.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(LeaveRequestResponse.from(created));
    }

    @Operation(summary = "Leave requests of a group")
    @GetMapping("/groups/{groupId}/leave-requests")
    public List<LeaveRequestResponse> leaveRequests(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return leaveService.list(groupId, currentUser).stream().map(LeaveRequestResponse::from).toList();
    }

    @Operation(summary = "Approve a leave request")
    @PostMapping("/leave-requests/{id}/approve")
    @PreAuthorize(LEADER)
    public LeaveRequestResponse approveLeave(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return LeaveRequestResponse.from(leaveService.approve(id, currentUser));
    }

    @Operation(summary = "Reject a leave request")
    @PostMapping("/leave-requests/{id}/reject")
    @PreAuthorize(LEADER)
    public LeaveRequestResponse rejectLeave(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return LeaveRequestResponse.from(leaveService.reject(id, currentUser));
    }

    @Operation(summary = "Withdraw my leave request")
    @PostMapping("/leave-requests/{id}/withdraw")
    @PreAuthorize(STUDENT)
    public LeaveRequestResponse withdrawLeave(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return LeaveRequestResponse.from(leaveService.withdraw(id, currentUser));
    }

    // ------------------------------------------------------------------ settings

    /** YC14: how long an unanswered Apply/Invite stays open in this semester (default 48 hours). */
    @Operation(summary = "Get join settings of a semester (application/invite expiry)")
    @GetMapping("/semesters/{semester}/join-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public TtlResponse getTtl(@PathVariable String semester) {
        return settings(semester);
    }

    @Operation(summary = "Update join settings of a semester")
    @PutMapping("/semesters/{semester}/join-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public TtlResponse setTtl(@PathVariable String semester, @Valid @RequestBody TtlRequest request) {
        joinService.setTtlHours(semester, request.ttlHours());
        return settings(semester);
    }

    /** YC17 / YC21: end of the permitted period in which students form, join and leave groups themselves. */
    @Operation(summary = "Set the group formation deadline of a semester")
    @PutMapping("/semesters/{semester}/formation-deadline")
    @PreAuthorize("hasRole('ADMIN')")
    public TtlResponse setFormationDeadline(@PathVariable String semester, @RequestBody DeadlineRequest request) {
        formationWindow.setDeadline(semester, request.deadline());
        return settings(semester);
    }

    @Operation(summary = "My group formation window (open, deadline)")
    @GetMapping("/me/formation-window")
    public FormationWindowResponse formationWindow(@RequestParam String semester) {
        Instant deadline = formationWindow.deadline(semester);
        return new FormationWindowResponse(semester, formationWindow.ttlHours(semester), deadline,
                deadline == null || Instant.now().isBefore(deadline), GroupJoinService.MAX_OPEN_APPLICATIONS,
                StudentGroupService.MIN_MEMBERS, StudentGroupService.MAX_MEMBERS);
    }

    private TtlResponse settings(String semester) {
        return new TtlResponse(semester, formationWindow.ttlHours(semester), formationWindow.deadline(semester));
    }
}
