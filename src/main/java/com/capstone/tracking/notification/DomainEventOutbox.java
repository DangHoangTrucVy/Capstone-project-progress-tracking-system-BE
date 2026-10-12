package com.capstone.tracking.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        // Databases keep microseconds and round: an un-truncated "now" can be stored slightly in the future, so the
        // immediate delivery right after commit would skip the event and leave it to the next retry sweep.
        this.nextAttemptAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
