package com.capstone.tracking.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
/**
 * Forwards domain events to the {@link EventSink} only once the business transaction has committed, so nothing is
 * announced for work that rolled back. The event is durably recorded before commit; failed delivery is retried.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventRelay {

    private final DomainEventOutboxRepository repository;
    private final ObjectMapper mapper;
    private final DomainEventDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void persist(DomainEvent event) {
        try {
            repository.save(new DomainEventOutbox(event.eventId(), mapper.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot persist domain event", e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(DomainEvent event) {
        try {
            dispatcher.deliver(event.eventId());
        } catch (RuntimeException e) {
            log.error("Could not deliver {} {}", event.type(), event.eventId(), e);
        }
    }
}
