package com.capstone.tracking.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened to a group, published by services with Spring's ApplicationEventPublisher and delivered
 * after the transaction commits. It is also the SQS message body (JSON), so it only carries ids and short texts;
 * {@link NotificationHandler} looks the rest up when it runs.
 *
 * @param label short human text for the notification, e.g. the document title or "tuần 3"
 * @param instructorId the instructor concerned when it is not the group's supervisor (a booked slot's owner)
 * @param details longer feedback shown with the notification and in the email (council feedback, flag reason...)
 * @param deadline the date the group must act by, when there is one (council review deadline, revision deadline...)
 */
public record DomainEvent(
        UUID eventId,
        DomainEventType type,
        Instant occurredAt,
        UUID groupId,
        UUID entityId,
        UUID actorId,
        String label,
        UUID instructorId,
        String details,
        Instant deadline
) {
    public static DomainEvent of(DomainEventType type, UUID groupId, UUID entityId, UUID actorId, String label) {
        return new DomainEvent(UUID.randomUUID(), type, Instant.now(), groupId, entityId, actorId, label, null, null, null);
    }

    public DomainEvent withInstructor(UUID instructorId) {
        return new DomainEvent(eventId, type, occurredAt, groupId, entityId, actorId, label, instructorId, details, deadline);
    }

    public DomainEvent withDetails(String details, Instant deadline) {
        return new DomainEvent(eventId, type, occurredAt, groupId, entityId, actorId, label, instructorId, details, deadline);
    }
}
