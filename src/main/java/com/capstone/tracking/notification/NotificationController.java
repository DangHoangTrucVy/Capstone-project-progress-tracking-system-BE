package com.capstone.tracking.notification;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.notification.dto.NotificationResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "The current user's in-app notifications")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationStreamRegistry streams;

    /**
     * Real-time feed for the Overview screen: an SSE stream of "notification" events. Browsers' EventSource cannot
     * set headers, so this endpoint also accepts the JWT as ?access_token=.
     */
    @Operation(summary = "Real-time notification stream (Server-Sent Events)")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal User currentUser) {
        return streams.open(currentUser.getId());
    }

    @Operation(summary = "My notifications")
    @GetMapping
    public Page<NotificationResponse> list(@RequestParam(defaultValue = "false") boolean unreadOnly, @ParameterObject Pageable pageable,
                                           @AuthenticationPrincipal User currentUser) {
        return notificationService.list(currentUser, unreadOnly, pageable).map(NotificationResponse::from);
    }

    @Operation(summary = "Number of unread notifications")
    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal User currentUser) {
        return Map.of("unread", notificationService.unreadCount(currentUser));
    }

    @Operation(summary = "Mark a notification as read")
    @PutMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return NotificationResponse.from(notificationService.markRead(id, currentUser));
    }

    @Operation(summary = "Mark all notifications as read")
    @PutMapping("/read-all")
    public Map<String, Integer> markAllRead(@AuthenticationPrincipal User currentUser) {
        return Map.of("updated", notificationService.markAllRead(currentUser));
    }
}
