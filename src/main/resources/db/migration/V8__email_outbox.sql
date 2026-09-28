-- Outgoing emails are written here in the same transaction as the notifications they belong to and sent by a
-- background dispatcher (EmailOutboxDispatcher) with retries, so SMTP never slows down or breaks a user's request.
-- event_id is unique: a redelivered domain event cannot queue the same email twice.

CREATE TABLE email_outbox (
    id                  UUID PRIMARY KEY,
    event_id            UUID NOT NULL UNIQUE,
    recipients_to       TEXT NOT NULL,
    recipients_cc       TEXT,
    subject             VARCHAR(255) NOT NULL,
    body                TEXT NOT NULL,
    status              VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts            INT NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP NOT NULL,
    last_error          TEXT,
    sent_at             TIMESTAMP,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL
);

-- The dispatcher's query: pending emails that are due.
CREATE INDEX idx_email_outbox_pending ON email_outbox (status, next_attempt_at);
