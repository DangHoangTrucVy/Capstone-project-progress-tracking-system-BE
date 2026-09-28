package com.capstone.tracking.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;

@Component @Slf4j
public class DomainEventDispatcher {
    private final DomainEventOutboxRepository repository;
    private final EventSink sink;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public DomainEventDispatcher(DomainEventOutboxRepository repository, EventSink sink, ObjectMapper mapper,
                                 PlatformTransactionManager manager) {
        this.repository = repository;
        this.sink = sink;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(initialDelayString = "${app.messaging.dispatch-delay-ms:5000}",
               fixedDelayString = "${app.messaging.dispatch-delay-ms:5000}")
    public void retryPending() {
        for (UUID id : repository.due(Instant.now(), PageRequest.of(0, 20))) deliver(id);
    }

    public void deliver(UUID id) {
        transaction.executeWithoutResult(status -> repository.lockById(id).ifPresent(entry -> {
            if (entry.getDeliveredAt() != null || entry.getNextAttemptAt().isAfter(Instant.now())) return;
            entry.setAttempts(entry.getAttempts() + 1);
            try {
                sink.send(mapper.readValue(entry.getPayload(), DomainEvent.class));
                entry.setDeliveredAt(Instant.now());
                entry.setLastError(null);
            } catch (Exception e) {
                entry.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                entry.setNextAttemptAt(Instant.now().plusSeconds(Math.min(3600, 5L << Math.min(entry.getAttempts(), 9))));
                log.warn("Domain event {} will be retried", id, e);
            }
        }));
    }
}
