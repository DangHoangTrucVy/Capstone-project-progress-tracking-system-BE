package com.capstone.tracking.overview;

import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Group Overview", description = "Leader dashboard: status, badges, progress, schedule in one call")
@SecurityRequirement(name = "bearerAuth")
public class GroupOverviewController {

    private final GroupOverviewService overviewService;

    @Operation(summary = "Group overview", description = "Refetch when /api/v1/notifications/stream pushes a notification for this group.")
    @GetMapping("/api/v1/groups/{groupId}/overview")
    public GroupOverviewResponse overview(@PathVariable UUID groupId, @AuthenticationPrincipal User currentUser) {
        return overviewService.overview(groupId, currentUser);
    }
}
