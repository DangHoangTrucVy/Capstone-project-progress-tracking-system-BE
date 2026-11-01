package com.capstone.tracking.team;

import com.capstone.tracking.team.dto.GroupResponse;
import com.capstone.tracking.team.dto.LeaveRequestResponse;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.dto.ProfileView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Groups", description = "Creating a group, the Leader's settings, lock, kick, leaving (6.1, 6.3, 6.4) and search (8.2)")
@SecurityRequirement(name = "bearerAuth")
public class GroupController {

    private final GroupService groups;
    private final GroupViews views;
    private final SearchService search;

    public record LeaveBody(String reason) {}

    public record LeaveResponse(boolean left, LeaveRequestResponse request) {}

    @Operation(summary = "Create a group in a subject; the creator becomes its Leader (students, before the deadline)")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping("/subjects/{subjectId}/groups")
    public ResponseEntity<GroupResponse> create(@PathVariable UUID subjectId, @RequestBody GroupService.GroupRequest request,
                                                @AuthenticationPrincipal User currentUser) {
        StudentGroup created = groups.create(subjectId, request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/groups/" + created.getId()))
                .body(views.view(groups.get(created.getId()), currentUser));
    }

    @Operation(summary = "Get a group")
    @GetMapping("/groups/{groupId}")
    public GroupResponse get(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return views.view(groups.get(groupId), currentUser);
    }

    @Operation(summary = "Rename the group or change the skills it looks for (Leader)")
    @PutMapping("/groups/{groupId}")
    public GroupResponse update(@PathVariable UUID groupId, @RequestBody GroupService.GroupRequest request,
                                @AuthenticationPrincipal User currentUser) {
        groups.update(groupId, request, currentUser);
        return views.view(groups.get(groupId), currentUser);
    }

    @Operation(summary = "Lock the group: no new applications (Leader, before the deadline)")
    @PostMapping("/groups/{groupId}/lock")
    public GroupResponse lock(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        groups.setLocked(groupId, true, currentUser);
        return views.view(groups.get(groupId), currentUser);
    }

    @Operation(summary = "Unlock the group (Leader, before the deadline)")
    @PostMapping("/groups/{groupId}/unlock")
    public GroupResponse unlock(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        groups.setLocked(groupId, false, currentUser);
        return views.view(groups.get(groupId), currentUser);
    }

    @Operation(summary = "Remove a member from the group (Leader, before the deadline)")
    @DeleteMapping("/groups/{groupId}/members/{userId}")
    public ResponseEntity<Void> kick(@PathVariable UUID groupId, @PathVariable UUID userId,
                                     @AuthenticationPrincipal User currentUser) {
        groups.kick(groupId, userId, currentUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Leave the group: at once when allowed, otherwise a request to the Leader or the Moderator")
    @PostMapping("/groups/{groupId}/leave")
    public LeaveResponse leave(@PathVariable UUID groupId, @RequestBody(required = false) LeaveBody body,
                               @AuthenticationPrincipal User currentUser) {
        GroupService.LeaveOutcome outcome = groups.leave(groupId, body == null ? null : body.reason(), currentUser);
        return new LeaveResponse(outcome.left(),
                outcome.request() == null ? null : LeaveRequestResponse.from(outcome.request()));
    }

    @Operation(summary = "Leader: students of my subject and major without a group, by name, code or skill")
    @GetMapping("/subjects/{subjectId}/candidates")
    public List<ProfileView> candidates(@PathVariable UUID subjectId, @RequestParam(required = false) String q,
                                        @RequestParam(required = false) String skill,
                                        @AuthenticationPrincipal User currentUser) {
        return search.candidates(subjectId, q, skill, currentUser);
    }

    @Operation(summary = "Student: open groups of my subject and major, by name or wanted skill")
    @GetMapping("/subjects/{subjectId}/open-groups")
    public List<GroupResponse> openGroups(@PathVariable UUID subjectId, @RequestParam(required = false) String q,
                                          @RequestParam(required = false) String skill,
                                          @AuthenticationPrincipal User currentUser) {
        return search.openGroups(subjectId, q, skill, currentUser);
    }
}
