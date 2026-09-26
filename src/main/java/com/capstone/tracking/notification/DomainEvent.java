package com.capstone.tracking.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened to a group, published by services with Spring's ApplicationEventPublisher and delivered
 * after the transaction commits. It is also the SQS message body (JSON), so it only carries ids and a label;
 * {@link NotificationHandler} looks the rest up when it runs.
 *
 * @param label short human text for the notification, e.g. the document title or "tuần 3"
 * @param instructorId the instructor concerned when it is not the group's supervisor (a booked slot's owner)
 */
public record DomainEvent(
        UUID eventId,
        DomainEventType type,
        Instant occurredAt,
        UUID groupId,
        UUID entityId,
        UUID actorId,
        String label,
        UUID instructorId
) {
    public static DomainEvent of(DomainEventType type, UUID groupId, UUID entityId, UUID actorId, String label) {
        return new DomainEvent(UUID.randomUUID(), type, Instant.now(), groupId, entityId, actorId, label, null);
    }

    public DomainEvent withInstructor(UUID instructorId) {
        return new DomainEvent(eventId, type, occurredAt, groupId, entityId, actorId, label, instructorId);
    }
}
