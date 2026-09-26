package com.capstone.tracking.notification.dto;

import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        DomainEventType type,
        String message,
        UUID groupId,
        UUID entityId,
        boolean read,
        Instant readAt,
        Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getMessage(), n.getGroupId(), n.getEntityId(),
                n.getReadAt() != null, n.getReadAt(), n.getCreatedAt());
    }
}
