package com.capstone.tracking.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/** Durable handoff from the business transaction to the notification sink. */
@Entity @Table(name = "domain_event_outbox") @Getter @Setter @NoArgsConstructor
public class DomainEventOutbox {
    @Id private UUID id;
    @Column(nullable = false, columnDefinition = "TEXT") private String payload;
    @Column(nullable = false) private Instant nextAttemptAt;
    @Column(nullable = false) private int attempts;
    private Instant deliveredAt;
    @Column(columnDefinition = "TEXT") private String lastError;

    public DomainEventOutbox(UUID id, String payload) {
        this.id = id;
        this.payload = payload;
        this.nextAttemptAt = Instant.now();
    }
}
