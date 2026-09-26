-- In-app notifications produced from domain events (in-process, or through SQS when app.messaging.type=sqs).
-- event_id + recipient_id is unique so a redelivered SQS message cannot notify the same person twice.

CREATE TABLE notifications (
    id              UUID PRIMARY KEY,
    event_id        UUID NOT NULL,
    recipient_id    UUID NOT NULL REFERENCES users (id),
    type            VARCHAR(40)  NOT NULL,
    message         VARCHAR(500) NOT NULL,
    group_id        UUID REFERENCES student_groups (id),
    entity_id       UUID,
    read_at         TIMESTAMP,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_notifications_event_recipient UNIQUE (event_id, recipient_id)
);

CREATE INDEX idx_notifications_recipient_read ON notifications (recipient_id, read_at);
