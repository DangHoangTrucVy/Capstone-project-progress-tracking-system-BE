package com.capstone.tracking.notification.email;

import com.capstone.tracking.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** An email waiting to be (or already) sent by {@link EmailOutboxDispatcher}. Addresses are stored comma-separated. */
@Entity
@Table(name = "email_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailOutbox extends BaseEntity {

    public enum Status {
        PENDING,
        SENT,
        /** Gave up after {@link EmailOutboxDispatcher#MAX_ATTEMPTS} attempts; see lastError. */
        FAILED
    }

    @Column(nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "recipients_to", nullable = false, columnDefinition = "TEXT")
    private String recipientsTo;

    @Column(name = "recipients_cc", columnDefinition = "TEXT")
    private String recipientsCc;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    private Instant sentAt;

    public static EmailOutbox of(UUID eventId, EmailMessage message) {
        return EmailOutbox.builder()
                .eventId(eventId)
                .recipientsTo(String.join(",", message.to()))
                .recipientsCc(String.join(",", message.cc()))
                .subject(message.subject().length() > 255 ? message.subject().substring(0, 254) + "…" : message.subject())
                .body(message.body())
                .status(Status.PENDING)
                .nextAttemptAt(Instant.now())
                .build();
    }

    public EmailMessage toMessage() {
        return new EmailMessage(split(recipientsTo), split(recipientsCc), subject, body);
    }

    private static List<String> split(String addresses) {
        return addresses == null || addresses.isBlank() ? List.of() : Arrays.asList(addresses.split(","));
    }
}
