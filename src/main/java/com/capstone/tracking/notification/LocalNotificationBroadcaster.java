package com.capstone.tracking.notification;

import com.capstone.tracking.notification.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Single instance: the streams are all in this JVM. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalNotificationBroadcaster implements NotificationBroadcaster {

    private final NotificationStreamRegistry streams;

    @Override
    public void broadcast(UUID recipientId, NotificationResponse notification) {
        streams.push(recipientId, notification);
    }
}
