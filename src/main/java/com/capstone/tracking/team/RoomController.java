package com.capstone.tracking.team;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.team.dto.JoinRequestResponse;
import com.capstone.tracking.team.dto.LeaveRequestResponse;
import com.capstone.tracking.team.dto.RoomResponse;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.dto.ProfileView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
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
@Tag(name = "Rooms", description = "Rooms created by the Moderator; students join freely, the first one in is the Leader; "
        + "lock, kick, leave, finalize request and approval (6, 7.6), search (8.2)")
@SecurityRequirement(name = "bearerAuth")
public class RoomController {

    private final GroupService rooms;
    private final GroupViews views;
    private final SearchService search;
    private final JoinRequestService joinRequests;

    public record MessageBody(String message) {}

    public record ReasonBody(String reason) {}

    public record DecisionBody(boolean approve, String note) {}

    public record JoinResponse(boolean joined, boolean leader, JoinRequestResponse request, RoomResponse room) {}

    public record LeaveResponse(boolean left, LeaveRequestResponse request) {}

    // ------------------------------------------------------------------ listing and creating

    @Operation(summary = "Rooms of the semester: the Moderator sees all (filter major/status/belowMinimum/q), a student "
            + "the rooms of their major")
    @GetMapping("/semesters/{semester}/rooms")
    public Page<RoomResponse> list(@PathVariable String semester, @RequestParam(required = false) UUID majorId,
                                   @RequestParam(required = false) RoomStatus status,
                                   @RequestParam(required = false) Boolean belowMinimum,
                                   @RequestParam(required = false) Boolean finalized,
                                   @RequestParam(required = false) String q,
                                   @ParameterObject @PageableDefault(size = 50) Pageable pageable,
                                   @AuthenticationPrincipal User currentUser) {
        if (pageable.getPageSize() > 200) {
            throw new BadRequestException("Page size must not exceed 200");
        }
        String query = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        List<RoomResponse> all = views.views(rooms.rooms(semester, majorId, currentUser), currentUser).stream()
                .filter(r -> status == null || r.status() == status)
                .filter(r -> belowMinimum == null || r.belowMinimum() == belowMinimum)
                .filter(r -> finalized == null || r.finalized() == finalized)
                .filter(r -> query.isEmpty() || r.name().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || (r.code() != null && r.code().toLowerCase(java.util.Locale.ROOT).contains(query))
                        || r.members().stream().anyMatch(m -> (m.fullName() + " " + m.studentCode())
                        .toLowerCase(java.util.Locale.ROOT).contains(query)))
                .toList();
        return new PageImpl<>(all.stream().skip(pageable.getOffset()).limit(pageable.getPageSize()).toList(), pageable,
                all.size());
    }

    @Operation(summary = "Create rooms in one major (Moderator, before round 1); without count: the missing rooms of the "
            + "plan = ceil(students / minimum room size) - existing rooms")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping("/semesters/{semester}/rooms")
    public ResponseEntity<List<RoomResponse>> create(@PathVariable String semester,
                                                     @RequestBody GroupService.CreateRooms request,
                                                     @AuthenticationPrincipal User currentUser) {
        List<StudentGroup> created = rooms.createRooms(semester, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(views.views(created, currentUser));
    }

    @Operation(summary = "Room plan per major: students, rooms, suggested rooms (Moderator)")
    @GetMapping("/semesters/{semester}/rooms/plan")
    public List<GroupService.RoomPlan> plan(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        return rooms.plan(semester, currentUser);
    }

    @Operation(summary = "Rooms waiting for the Moderator's finalize approval")
    @GetMapping("/semesters/{semester}/finalize-requests")
    public List<RoomResponse> finalizeRequests(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        return views.views(rooms.finalizeRequests(semester, currentUser), currentUser);
    }

    @Operation(summary = "Student: the rooms of my major by name or wanted skill; available=true keeps rooms I can enter")
    @GetMapping("/semesters/{semester}/rooms/search")
    public List<RoomResponse> searchRooms(@PathVariable String semester, @RequestParam(required = false) String q,
                                          @RequestParam(required = false) String skill,
                                          @RequestParam(defaultValue = "false") boolean available,
                                          @AuthenticationPrincipal User currentUser) {
        return search.rooms(semester, q, skill, available, currentUser);
    }

    @Operation(summary = "Leader (or Moderator with majorId): students of the major without a room, by name, code or skill")
    @GetMapping("/semesters/{semester}/candidates")
    public List<ProfileView> candidates(@PathVariable String semester, @RequestParam(required = false) UUID majorId,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) String skill,
                                        @AuthenticationPrincipal User currentUser) {
        return search.candidates(semester, majorId, q, skill, currentUser);
    }

    @Operation(summary = "My requests to join locked rooms and the invites I received in a semester")
    @GetMapping("/semesters/{semester}/my-requests")
    public List<JoinRequestResponse> myRequests(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        return joinRequests.mine(semester, currentUser);
    }

    // ------------------------------------------------------------------ one room

    @Operation(summary = "Get a room")
    @GetMapping("/rooms/{roomId}")
    public RoomResponse get(@PathVariable UUID roomId, @AuthenticationPrincipal User currentUser) {
        return views.view(rooms.get(roomId), currentUser);
    }

    @Operation(summary = "Leader: rename / wanted skills (before the deadline, not finalized); Moderator: also minSize")
    @PutMapping("/rooms/{roomId}")
    public RoomResponse update(@PathVariable UUID roomId, @RequestBody GroupService.RoomUpdate request,
                               @AuthenticationPrincipal User currentUser) {
        rooms.update(roomId, request, currentUser);
        return views.view(rooms.get(roomId), currentUser);
    }

    @Operation(summary = "Join a room: an empty room makes me its Leader, an open room takes me at once, a locked room "
            + "creates a request to the Leader")
    @PostMapping("/rooms/{roomId}/join")
    public JoinResponse join(@PathVariable UUID roomId, @RequestBody(required = false) MessageBody body,
                             @AuthenticationPrincipal User currentUser) {
        GroupService.JoinOutcome outcome = rooms.join(roomId, body == null ? null : body.message(), currentUser);
        return new JoinResponse(outcome.joined(), outcome.leader(),
                outcome.request() == null ? null : joinRequests.get(outcome.request().getId(), currentUser),
                views.view(rooms.get(roomId), currentUser));
    }

    @Operation(summary = "Leave the room: at once when allowed, otherwise a request to the Leader or the Moderator")
    @PostMapping("/rooms/{roomId}/leave")
    public LeaveResponse leave(@PathVariable UUID roomId, @RequestBody(required = false) ReasonBody body,
                               @AuthenticationPrincipal User currentUser) {
        GroupService.LeaveOutcome outcome = rooms.leave(roomId, body == null ? null : body.reason(), currentUser);
        return new LeaveResponse(outcome.left(),
                outcome.request() == null ? null : LeaveRequestResponse.from(outcome.request()));
    }

    @Operation(summary = "Lock the room: joining then takes a request the Leader accepts (Leader, before the deadline)")
    @PostMapping("/rooms/{roomId}/lock")
    public RoomResponse lock(@PathVariable UUID roomId, @AuthenticationPrincipal User currentUser) {
        rooms.setLocked(roomId, true, currentUser);
        return views.view(rooms.get(roomId), currentUser);
    }

    @Operation(summary = "Unlock the room (Leader, before the deadline)")
    @PostMapping("/rooms/{roomId}/unlock")
    public RoomResponse unlock(@PathVariable UUID roomId, @AuthenticationPrincipal User currentUser) {
        rooms.setLocked(roomId, false, currentUser);
        return views.view(rooms.get(roomId), currentUser);
    }

    @Operation(summary = "Remove a member (Leader, before the deadline, room not finalized)")
    @DeleteMapping("/rooms/{roomId}/members/{userId}")
    public ResponseEntity<Void> kick(@PathVariable UUID roomId, @PathVariable UUID userId,
                                     @AuthenticationPrincipal User currentUser) {
        rooms.kick(roomId, userId, currentUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Báo chốt: the Leader asks the Moderator to finalize (at least the room's minimum size)")
    @PostMapping("/rooms/{roomId}/finalize-request")
    public RoomResponse requestFinalize(@PathVariable UUID roomId, @AuthenticationPrincipal User currentUser) {
        rooms.requestFinalize(roomId, currentUser);
        return views.view(rooms.get(roomId), currentUser);
    }

    @Operation(summary = "The Leader withdraws the finalize request")
    @DeleteMapping("/rooms/{roomId}/finalize-request")
    public RoomResponse cancelFinalize(@PathVariable UUID roomId, @AuthenticationPrincipal User currentUser) {
        rooms.cancelFinalize(roomId, currentUser);
        return views.view(rooms.get(roomId), currentUser);
    }

    @Operation(summary = "Moderator: approve (the room becomes a group with the next code, e.g. SP26-SE-001) or send back")
    @PostMapping("/rooms/{roomId}/finalize-decision")
    public RoomResponse decideFinalize(@PathVariable UUID roomId, @RequestBody DecisionBody body,
                                       @AuthenticationPrincipal User currentUser) {
        rooms.decideFinalize(roomId, body.approve(), body.note(), currentUser);
        return views.view(rooms.get(roomId), currentUser);
    }
}
