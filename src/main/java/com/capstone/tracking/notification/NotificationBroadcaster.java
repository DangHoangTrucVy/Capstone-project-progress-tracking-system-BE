package com.capstone.tracking.notification;

import com.capstone.tracking.notification.dto.NotificationResponse;

import java.util.UUID;

/**
 * Delivers a freshly written notification to the recipient's open SSE streams, wherever they are connected:
 * {@link LocalNotificationBroadcaster} for a single instance, {@link RedisNotificationBroadcaster} (Redis pub/sub,
 * {@code app.redis.enabled=true}) when several instances run behind a load balancer or SQS.
 */
public interface NotificationBroadcaster {

    void broadcast(UUID recipientId, NotificationResponse notification);
}
