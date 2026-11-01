package com.capstone.tracking.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Something people must be told about (8.3), published by services with Spring's ApplicationEventPublisher and
 * delivered after the transaction commits. The publisher decides the text and who receives it; it is also the SQS
 * message body (JSON), so it only carries ids and short texts.
 *
 * @param groupId    the group concerned, for FE deep links (may be null)
 * @param entityId   the request / scope / subject concerned (may be null)
 * @param message    the notification text (Vietnamese, shown as is)
 * @param details    a reason or note shown under the message and in the email
 * @param deadline   the date to act by, when there is one
 * @param recipients the users to notify; the actor is never notified of their own action
 */
public record DomainEvent(
        UUID eventId,
        DomainEventType type,
        Instant occurredAt,
        UUID groupId,
        UUID entityId,
        UUID actorId,
        String message,
        String details,
        Instant deadline,
        List<UUID> recipients
) {
    public static DomainEvent of(DomainEventType type, String message, Collection<UUID> recipients) {
        return new DomainEvent(UUID.randomUUID(), type, Instant.now(), null, null, null, message, null, null,
                recipients.stream().filter(Objects::nonNull).distinct().toList());
    }

    public static DomainEvent of(DomainEventType type, String message, UUID recipient) {
        return of(type, message, recipient == null ? List.of() : List.of(recipient));
    }

    public DomainEvent about(UUID groupId, UUID entityId) {
        return new DomainEvent(eventId, type, occurredAt, groupId, entityId, actorId, message, details, deadline, recipients);
    }

    public DomainEvent by(UUID actorId) {
        return new DomainEvent(eventId, type, occurredAt, groupId, entityId, actorId, message, details, deadline, recipients);
    }

    public DomainEvent withDetails(String details, Instant deadline) {
        return new DomainEvent(eventId, type, occurredAt, groupId, entityId, actorId, message, details, deadline, recipients);
    }
}
