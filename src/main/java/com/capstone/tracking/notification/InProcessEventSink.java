package com.capstone.tracking.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** No broker: handles the event right away in the request thread (after commit). */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.messaging", name = "type", havingValue = "inprocess", matchIfMissing = true)
public class InProcessEventSink implements EventSink {

    private final NotificationHandler notificationHandler;

    @Override
    public void send(DomainEvent event) {
        notificationHandler.handle(event);
    }
}
