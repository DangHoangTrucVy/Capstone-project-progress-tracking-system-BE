package com.capstone.tracking.team;

import com.capstone.tracking.team.dto.RoomResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Moderation", description = "The Moderator arranges rooms in every phase until formation ends, locked rooms "
        + "included (7.2)")
@SecurityRequirement(name = "bearerAuth")
public class ModerationController {

    private final ModerationService moderation;
    private final GroupViews views;
    private final GroupCore core;

    public record StudentBody(UUID userId) {}

    public record RemoveBody(UUID userId, String reason) {}

    public record DisbandBody(List<UUID> roomIds, boolean allBelowMinimum, UUID majorId, String reason) {}

    public record MergeBody(UUID sourceRoomId) {}

    @Operation(summary = "Put a student without a room into this room (locked rooms too; into an empty room as its Leader; "
            + "finalized groups only in the supplement phase)")
    @PostMapping("/rooms/{roomId}/moderation/add")
    public RoomResponse add(@PathVariable UUID roomId, @RequestBody StudentBody body,
                            @AuthenticationPrincipal User currentUser) {
        moderation.addMember(roomId, body.userId(), currentUser);
        return views.view(core.get(roomId), currentUser);
    }

    @Operation(summary = "Move a student from their room into this room")
    @PostMapping("/rooms/{roomId}/moderation/move")
    public RoomResponse move(@PathVariable UUID roomId, @RequestBody StudentBody body,
                             @AuthenticationPrincipal User currentUser) {
        moderation.move(roomId, body.userId(), currentUser);
        return views.view(core.get(roomId), currentUser);
    }

    @Operation(summary = "Take a student out of this room or group (finalized groups too), with a required reason")
    @PostMapping("/rooms/{roomId}/moderation/remove")
    public RoomResponse remove(@PathVariable UUID roomId, @RequestBody RemoveBody body,
                               @AuthenticationPrincipal User currentUser) {
        moderation.removeMember(roomId, body.userId(), body.reason(), currentUser);
        return views.view(core.get(roomId), currentUser);
    }

    @Operation(summary = "Appoint a current member as Leader directly")
    @PostMapping("/rooms/{roomId}/moderation/leader")
    public RoomResponse appoint(@PathVariable UUID roomId, @RequestBody StudentBody body,
                                @AuthenticationPrincipal User currentUser) {
        return views.view(moderation.appointLeader(roomId, body.userId(), currentUser), currentUser);
    }

    @Operation(summary = "Merge another room into this room (up to the maximum size); the source goes back to empty")
    @PostMapping("/rooms/{roomId}/moderation/merge")
    public RoomResponse merge(@PathVariable UUID roomId, @RequestBody MergeBody body,
                              @AuthenticationPrincipal User currentUser) {
        return views.view(moderation.merge(roomId, body.sourceRoomId(), currentUser), currentUser);
    }

    @Operation(summary = "Disband rooms back to empty: the given roomIds, or allBelowMinimum=true for every unfinalized "
            + "room with members below its minimum (optionally one major)")
    @PostMapping("/semesters/{semester}/rooms/disband")
    public ModerationService.DisbandResult disband(@PathVariable String semester, @RequestBody DisbandBody body,
                                                   @AuthenticationPrincipal User currentUser) {
        return moderation.disband(semester, body.roomIds(), body.allBelowMinimum(), body.majorId(), body.reason(),
                currentUser);
    }
}
