package com.capstone.tracking.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Forwards domain events to the {@link EventSink} only once the business transaction has committed, so nothing is
 * announced for work that rolled back. A sink failure is logged, never surfaced: the user's action already succeeded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventRelay {

    private final EventSink eventSink;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(DomainEvent event) {
        try {
            eventSink.send(event);
        } catch (RuntimeException e) {
            log.error("Could not deliver {} {}", event.type(), event.eventId(), e);
        }
    }
}
