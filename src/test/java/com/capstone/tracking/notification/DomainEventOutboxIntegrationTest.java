package com.capstone.tracking.notification;

import com.capstone.tracking.WorkflowTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DomainEventOutboxIntegrationTest extends WorkflowTestSupport {
    @Autowired TransactionTemplate transactions;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired DomainEventOutboxRepository outbox;
    @Autowired DomainEventDispatcher dispatcher;
    @MockBean EventSink sink;

    @Test
    void failedDeliveryIsDurableAndRetryDoesNotSendAnAlreadyDeliveredEvent() {
        DomainEvent event = event();
        doThrow(new IllegalStateException("Broker unavailable")).when(sink).send(any());
        transactions.executeWithoutResult(status -> publisher.publishEvent(event));
        DomainEventOutbox saved = outbox.findById(event.eventId()).orElseThrow();
        assertThat(saved.getDeliveredAt()).isNull();
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getLastError()).contains("Broker unavailable");
        saved.setNextAttemptAt(Instant.now().minusSeconds(1));
        outbox.save(saved);
        doNothing().when(sink).send(any());
        dispatcher.deliver(event.eventId());
        dispatcher.deliver(event.eventId());
        verify(sink, times(2)).send(event);
        assertThat(outbox.findById(event.eventId()).orElseThrow().getDeliveredAt()).isNotNull();
    }

    @Test
    void rollbackDoesNotPublishOrLeavePendingWork() {
        DomainEvent event = event();
        transactions.executeWithoutResult(status -> {
            publisher.publishEvent(event);
            status.setRollbackOnly();
        });
        assertThat(outbox.findById(event.eventId())).isEmpty();
        verifyNoInteractions(sink);
    }

    private DomainEvent event() {
        return DomainEvent.of(DomainEventType.TOPIC_APPROVED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Topic");
    }
}
