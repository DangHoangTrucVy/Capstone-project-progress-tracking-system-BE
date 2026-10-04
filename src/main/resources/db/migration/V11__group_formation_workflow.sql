-- Group formation workflow (YC01-YC22): eligibility flag, Apply/Invite requests, advisory votes,
-- leave requests, roster approval, roster lock and per-semester request TTL.

-- ---------------------------------------------------------------- users: eligibility to do the capstone (YC03)
ALTER TABLE users ADD COLUMN eligible BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN ineligible_reason VARCHAR(500);

-- ---------------------------------------------------------------- student_groups: roster approval + lock (YC16, YC19)
ALTER TABLE student_groups ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE student_groups ADD COLUMN roster_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
    CHECK (roster_status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'));
ALTER TABLE student_groups ADD COLUMN roster_note VARCHAR(1000);

-- ---------------------------------------------------------------- Apply / Invite (YC08-YC15)
CREATE TABLE group_join_requests (
    id                      UUID PRIMARY KEY,
    group_id                UUID NOT NULL REFERENCES student_groups (id),
    student_id              UUID NOT NULL REFERENCES users (id),
    type                    VARCHAR(10) NOT NULL CHECK (type IN ('APPLY', 'INVITE')),
    status                  VARCHAR(20) NOT NULL
                             CHECK (status IN ('PENDING', 'APPROVED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'CANCELLED')),
    message                 VARCHAR(1000),
    created_by              UUID NOT NULL REFERENCES users (id),
    source_application_id   UUID REFERENCES group_join_requests (id),
    expires_at              TIMESTAMP NOT NULL,
    responded_at            TIMESTAMP,
    created_at              TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP NOT NULL
);
CREATE INDEX idx_group_join_requests_student ON group_join_requests (student_id, status);
CREATE INDEX idx_group_join_requests_group ON group_join_requests (group_id, type, status);
-- At most one open request of each type between a student and a group.
CREATE UNIQUE INDEX uq_group_join_requests_pending ON group_join_requests (group_id, student_id, type)
    WHERE status = 'PENDING';

-- Advisory votes on an Apply (YC12): reference only, the leader decides without waiting for them.
CREATE TABLE group_application_votes (
    id              UUID PRIMARY KEY,
    application_id  UUID NOT NULL REFERENCES group_join_requests (id),
    voter_id        UUID NOT NULL REFERENCES users (id),
    vote            VARCHAR(10) NOT NULL CHECK (vote IN ('SUPPORT', 'OPPOSE')),
    comment         VARCHAR(1000),
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_group_application_votes UNIQUE (application_id, voter_id)
);

-- ---------------------------------------------------------------- leaving a group (YC17)
CREATE TABLE member_leave_requests (
    id              UUID PRIMARY KEY,
    group_id        UUID NOT NULL REFERENCES student_groups (id),
    user_id         UUID NOT NULL REFERENCES users (id),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    reason          VARCHAR(1000),
    decided_by      UUID REFERENCES users (id),
    decided_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);
CREATE INDEX idx_member_leave_requests_group ON member_leave_requests (group_id, status);
CREATE UNIQUE INDEX uq_member_leave_requests_pending ON member_leave_requests (group_id, user_id)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------- Admin-configurable request lifetime per semester (YC14)
CREATE TABLE semester_join_settings (
    semester    VARCHAR(20) PRIMARY KEY,
    ttl_hours   INTEGER NOT NULL CHECK (ttl_hours > 0)
);
