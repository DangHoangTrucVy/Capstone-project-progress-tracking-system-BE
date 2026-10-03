-- Recruiting profile shown to a group while a student has an open Apply (YC22).
ALTER TABLE users ADD COLUMN bio VARCHAR(1000);
ALTER TABLE users ADD COLUMN skills VARCHAR(500);

-- The supervisor reports roster changes of a Locked group to the Admin, who is the only one who edits it (YC19, YC20).
CREATE TABLE roster_change_reports (
    id               UUID PRIMARY KEY,
    group_id         UUID NOT NULL REFERENCES student_groups (id),
    reported_by      UUID NOT NULL REFERENCES users (id),
    type             VARCHAR(30) NOT NULL CHECK (type IN ('MEMBER_CHANGE', 'LEADER_REPLACEMENT')),
    description      VARCHAR(1000) NOT NULL,
    status           VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    resolved_by      UUID REFERENCES users (id),
    resolved_at      TIMESTAMP,
    resolution_note  VARCHAR(1000),
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL
);
CREATE INDEX idx_roster_change_reports_status ON roster_change_reports (status, created_at);
CREATE INDEX idx_roster_change_reports_group ON roster_change_reports (group_id);
