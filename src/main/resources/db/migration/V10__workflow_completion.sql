ALTER TABLE artifact_submissions ADD COLUMN feedback TEXT;
ALTER TABLE artifact_submissions ADD COLUMN reviewed_by UUID REFERENCES users(id);
ALTER TABLE artifact_submissions ADD COLUMN reviewed_at TIMESTAMPTZ;

CREATE TABLE semester_calendars (
    semester VARCHAR(20) PRIMARY KEY,
    start_date DATE NOT NULL
);

-- Serialize schedule creation across application instances, including otherwise empty calendars.
CREATE TABLE schedule_mutex (
    id INTEGER PRIMARY KEY
);
INSERT INTO schedule_mutex (id) VALUES (1);

CREATE TABLE domain_event_outbox (
    id UUID PRIMARY KEY,
    payload TEXT NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    delivered_at TIMESTAMPTZ,
    last_error TEXT
);
CREATE INDEX idx_domain_event_outbox_due ON domain_event_outbox (next_attempt_at) WHERE delivered_at IS NULL;
