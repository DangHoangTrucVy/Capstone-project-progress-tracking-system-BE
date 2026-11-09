package com.capstone.tracking.team;

import com.capstone.tracking.team.LeaderTransferService.TransferResponse;
import com.capstone.tracking.team.dto.JoinRequestResponse;
import com.capstone.tracking.team.dto.LeaveRequestResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Requests", description = "Requests to join locked rooms, invites (6.2, 7.4), Likes (8.2), leave requests "
        + "and complaints (6.5), Leader transfer (6.6)")
@SecurityRequirement(name = "bearerAuth")
public class RequestController {

    private final JoinRequestService joinRequests;
    private final LeaveRequestService leaveRequests;
    private final LeaderTransferService transfers;

    public record InviteBody(UUID studentId, String studentCode, String message) {}

    public record ReasonBody(String reason) {}

    public record DecisionBody(boolean approve, String note) {}

    public record TransferBody(UUID toUserId, boolean leaveAfter) {}

    // ------------------------------------------------------------------ Apply / Invite

    @Operation(summary = "Leader invites a student of the same major without a room: before the deadline, or in the "
            + "supplement phase (no expiry, rooms below the maximum size)")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping("/rooms/{groupId}/invitations")
    public ResponseEntity<JoinRequestResponse> invite(@PathVariable UUID groupId, @RequestBody InviteBody body,
                                                      @AuthenticationPrincipal User currentUser) {
        JoinRequest saved = joinRequests.invite(groupId, body.studentId(), body.studentCode(), body.message(), currentUser);
        return ResponseEntity.created(URI.create("/api/v1/requests/" + saved.getId()))
                .body(joinRequests.get(saved.getId(), currentUser));
    }

    @Operation(summary = "The room's requests to join and invitations (members, Moderator)")
    @GetMapping("/rooms/{groupId}/requests")
    public List<JoinRequestResponse> forGroup(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return joinRequests.forGroup(groupId, currentUser);
    }

    @Operation(summary = "Get a request to join or an invitation (with the student's profile while it is pending)")
    @GetMapping("/requests/{requestId}")
    public JoinRequestResponse get(@PathVariable UUID requestId, @AuthenticationPrincipal User currentUser) {
        return joinRequests.get(requestId, currentUser);
    }

    @Operation(summary = "Accept: the Leader accepts a request to join or the student an invitation; the student joins now")
    @PostMapping("/requests/{requestId}/accept")
    public JoinRequestResponse accept(@PathVariable UUID requestId, @AuthenticationPrincipal User currentUser) {
        joinRequests.accept(requestId, currentUser);
        return joinRequests.get(requestId, currentUser);
    }

    @Operation(summary = "Reject: the Leader refuses a request (with a reason) or the student declines an invitation")
    @PostMapping("/requests/{requestId}/reject")
    public JoinRequestResponse reject(@PathVariable UUID requestId, @RequestBody(required = false) ReasonBody body,
                                      @AuthenticationPrincipal User currentUser) {
        joinRequests.reject(requestId, body == null ? null : body.reason(), currentUser);
        return joinRequests.get(requestId, currentUser);
    }

    @Operation(summary = "Withdraw: the student takes back a request, the Leader an invitation")
    @PostMapping("/requests/{requestId}/withdraw")
    public JoinRequestResponse withdraw(@PathVariable UUID requestId, @AuthenticationPrincipal User currentUser) {
        joinRequests.withdraw(requestId, currentUser);
        return joinRequests.get(requestId, currentUser);
    }

    @Operation(summary = "Like a candidate who asked to join (members of the room; advice only)")
    @PutMapping("/requests/{requestId}/like")
    public Map<String, Long> like(@PathVariable UUID requestId, @AuthenticationPrincipal User currentUser) {
        return Map.of("likes", joinRequests.like(requestId, true, currentUser));
    }

    @Operation(summary = "Remove my Like")
    @DeleteMapping("/requests/{requestId}/like")
    public Map<String, Long> unlike(@PathVariable UUID requestId, @AuthenticationPrincipal User currentUser) {
        return Map.of("likes", joinRequests.like(requestId, false, currentUser));
    }

    // ------------------------------------------------------------------ leave requests and complaints

    @Operation(summary = "The room's leave requests (the Leader and the Moderator see all, a member their own)")
    @GetMapping("/rooms/{groupId}/leave-requests")
    public List<LeaveRequestResponse> leaveRequestsOfGroup(@PathVariable UUID groupId,
                                                           @AuthenticationPrincipal User currentUser) {
        return leaveRequests.forGroup(groupId, currentUser);
    }

    @Operation(summary = "My leave requests")
    @GetMapping("/me/leave-requests")
    public List<LeaveRequestResponse> myLeaveRequests(@AuthenticationPrincipal User currentUser) {
        return leaveRequests.mine(currentUser);
    }

    @Operation(summary = "The Leader approves or refuses a leave request (before the deadline)")
    @PostMapping("/leave-requests/{requestId}/leader-decision")
    public LeaveRequestResponse leaderDecision(@PathVariable UUID requestId, @RequestBody DecisionBody body,
                                               @AuthenticationPrincipal User currentUser) {
        return LeaveRequestResponse.from(leaveRequests.leaderDecide(requestId, body.approve(), body.note(), currentUser));
    }

    @Operation(summary = "Complain to the Moderator about a refused leave request")
    @PostMapping("/leave-requests/{requestId}/complaint")
    public LeaveRequestResponse complain(@PathVariable UUID requestId, @RequestBody(required = false) ReasonBody body,
                                         @AuthenticationPrincipal User currentUser) {
        return LeaveRequestResponse.from(leaveRequests.escalate(requestId, body == null ? null : body.reason(), currentUser));
    }

    @Operation(summary = "Withdraw my pending leave request")
    @PostMapping("/leave-requests/{requestId}/withdraw")
    public LeaveRequestResponse withdrawLeave(@PathVariable UUID requestId, @AuthenticationPrincipal User currentUser) {
        return LeaveRequestResponse.from(leaveRequests.withdraw(requestId, currentUser));
    }

    @Operation(summary = "The Moderator decides a complaint or a request made after the deadline")
    @PostMapping("/leave-requests/{requestId}/moderator-decision")
    public LeaveRequestResponse moderatorDecision(@PathVariable UUID requestId, @RequestBody DecisionBody body,
                                                  @AuthenticationPrincipal User currentUser) {
        return LeaveRequestResponse.from(leaveRequests.moderatorDecide(requestId, body.approve(), body.note(), currentUser));
    }

    @Operation(summary = "Complaints and leave / transfer requests waiting for the Moderator in a semester")
    @GetMapping("/semesters/{semester}/complaints")
    public List<LeaveRequestResponse> complaints(@PathVariable String semester, @RequestParam(required = false) UUID majorId,
                                                 @AuthenticationPrincipal User currentUser) {
        return leaveRequests.waitingForModerator(semester, majorId, currentUser);
    }

    // ------------------------------------------------------------------ Leader transfer

    @Operation(summary = "Offer the Leader role to a member (optionally leaving the room when accepted)")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping("/rooms/{groupId}/leader-transfers")
    public ResponseEntity<TransferResponse> proposeTransfer(@PathVariable UUID groupId, @RequestBody TransferBody body,
                                                            @AuthenticationPrincipal User currentUser) {
        LeaderTransfer saved = transfers.propose(groupId, body.toUserId(), body.leaveAfter(), currentUser);
        return ResponseEntity.created(URI.create("/api/v1/leader-transfers/" + saved.getId()))
                .body(TransferResponse.from(saved));
    }

    @Operation(summary = "Accept the Leader role")
    @PostMapping("/leader-transfers/{transferId}/accept")
    public TransferResponse acceptTransfer(@PathVariable UUID transferId, @AuthenticationPrincipal User currentUser) {
        return TransferResponse.from(transfers.answer(transferId, true, currentUser));
    }

    @Operation(summary = "Decline the Leader role")
    @PostMapping("/leader-transfers/{transferId}/decline")
    public TransferResponse declineTransfer(@PathVariable UUID transferId, @AuthenticationPrincipal User currentUser) {
        return TransferResponse.from(transfers.answer(transferId, false, currentUser));
    }

    @Operation(summary = "Cancel my Leader transfer offer")
    @PostMapping("/leader-transfers/{transferId}/cancel")
    public TransferResponse cancelTransfer(@PathVariable UUID transferId, @AuthenticationPrincipal User currentUser) {
        return TransferResponse.from(transfers.cancel(transferId, currentUser));
    }

    @Operation(summary = "Leader transfers I offered or received")
    @GetMapping("/me/leader-transfers")
    public List<TransferResponse> myTransfers(@AuthenticationPrincipal User currentUser) {
        return transfers.mine(currentUser);
    }
}
