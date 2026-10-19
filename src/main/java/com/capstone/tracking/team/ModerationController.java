package com.capstone.tracking.team;

import com.capstone.tracking.team.dto.GroupResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Moderation", description = "The Moderator arranges groups after the deadline (7.1)")
@SecurityRequirement(name = "bearerAuth")
public class ModerationController {

    private final ModerationService moderation;
    private final GroupViews views;
    private final GroupCore core;

    public record StudentBody(UUID userId) {}

    public record RemoveBody(UUID userId, String reason) {}

    public record DissolveBody(List<UUID> groupIds, String reason) {}

    @Operation(summary = "Put a student without a group into this group (locked groups too, up to 5)")
    @PostMapping("/groups/{groupId}/moderation/add")
    public GroupResponse add(@PathVariable UUID groupId, @RequestBody StudentBody body,
                             @AuthenticationPrincipal User currentUser) {
        moderation.addMember(groupId, body.userId(), currentUser);
        return views.view(core.get(groupId), currentUser);
    }

    @Operation(summary = "Move a student from their group into this group")
    @PostMapping("/groups/{groupId}/moderation/move")
    public GroupResponse move(@PathVariable UUID groupId, @RequestBody StudentBody body,
                              @AuthenticationPrincipal User currentUser) {
        moderation.move(groupId, body.userId(), currentUser);
        return views.view(core.get(groupId), currentUser);
    }

    @Operation(summary = "Take a student out of this group, with a reason")
    @PostMapping("/groups/{groupId}/moderation/remove")
    public GroupResponse remove(@PathVariable UUID groupId, @RequestBody RemoveBody body,
                                @AuthenticationPrincipal User currentUser) {
        moderation.removeMember(groupId, body.userId(), body.reason(), currentUser);
        return views.view(core.get(groupId), currentUser);
    }

    @Operation(summary = "Create a group of students without one and appoint its Leader")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping("/scopes/{scopeId}/groups")
    public ResponseEntity<GroupResponse> create(@PathVariable UUID scopeId,
                                                @RequestBody ModerationService.CreateGroupRequest body,
                                                @AuthenticationPrincipal User currentUser) {
        StudentGroup group = moderation.createGroup(scopeId, body, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/groups/" + group.getId()))
                .body(views.view(core.get(group.getId()), currentUser));
    }

    @Operation(summary = "Dissolve the given groups at once (e.g. all groups below 3 members)")
    @PostMapping("/moderation/dissolve")
    public Map<String, Integer> dissolve(@RequestBody DissolveBody body, @AuthenticationPrincipal User currentUser) {
        return Map.of("dissolved", moderation.dissolve(body.groupIds(), body.reason(), currentUser));
    }
}
