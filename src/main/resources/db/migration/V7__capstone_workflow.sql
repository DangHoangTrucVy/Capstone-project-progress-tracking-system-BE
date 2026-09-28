-- Capstone workflow: Council role + campus sign-in (Giai đoạn 1), topic approval in up to 4 rounds (Giai đoạn 2),
-- warning flags (bước 4.4), Reviews 1-3 / closed council (Giai đoạn 5), final defenses (Giai đoạn 6) and richer
-- notifications (feedback + deadline, emailed to the group).

-- ---------------------------------------------------------------- users: COUNCIL role, campus
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('ADMIN', 'INSTRUCTOR', 'COUNCIL', 'GROUP_LEADER', 'STUDENT'));
ALTER TABLE users ADD COLUMN campus VARCHAR(20)
    CHECK (campus IN ('HA_NOI', 'HO_CHI_MINH', 'DA_NANG', 'CAN_THO', 'QUY_NHON'));

-- ---------------------------------------------------------------- student_groups: FAILED (lost the 2nd defense)
ALTER TABLE student_groups DROP CONSTRAINT IF EXISTS student_groups_status_check;
ALTER TABLE student_groups ADD CONSTRAINT student_groups_status_check
    CHECK (status IN ('FORMED', 'ACTIVE', 'COMPLETED', 'FAILED', 'ARCHIVED'));

-- ---------------------------------------------------------------- notifications: feedback + deadline
ALTER TABLE notifications ADD COLUMN details TEXT;
ALTER TABLE notifications ADD COLUMN deadline TIMESTAMP;

-- ---------------------------------------------------------------- topic approval (Giai đoạn 2)
CREATE TABLE topic_proposals (
    id                  UUID PRIMARY KEY,
    group_id            UUID NOT NULL REFERENCES student_groups (id),
    round               INT  NOT NULL CHECK (round BETWEEN 1 AND 4),
    status              VARCHAR(20) NOT NULL
                         CHECK (status IN ('PENDING_INSTRUCTOR', 'PENDING_COUNCIL', 'APPROVED', 'REJECTED')),
    submitted_by        UUID NOT NULL REFERENCES users (id),
    submitted_at        TIMESTAMP NOT NULL,
    instructor_note     TEXT,
    forwarded_by        UUID REFERENCES users (id),
    forwarded_at        TIMESTAMP,
    council_deadline    TIMESTAMP,
    decided_by          UUID REFERENCES users (id),
    decided_at          TIMESTAMP,
    council_feedback    TEXT,
    approved_topic_id   UUID REFERENCES topics (id),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    CONSTRAINT uq_topic_proposals_group_round UNIQUE (group_id, round)
);

CREATE INDEX idx_topic_proposals_status ON topic_proposals (status);

CREATE TABLE topic_proposal_items (
    id              UUID PRIMARY KEY,
    proposal_id     UUID NOT NULL REFERENCES topic_proposals (id) ON DELETE CASCADE,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    sort_order      INT NOT NULL,
    selected        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_topic_proposal_items_proposal ON topic_proposal_items (proposal_id);

CREATE TABLE proposal_rounds (
    id              UUID PRIMARY KEY,
    semester        VARCHAR(20) NOT NULL,
    round_number    INT NOT NULL CHECK (round_number BETWEEN 2 AND 4),
    opens_at        TIMESTAMP NOT NULL,
    closes_at       TIMESTAMP NOT NULL,
    closed          BOOLEAN NOT NULL DEFAULT FALSE,
    opened_by       UUID NOT NULL REFERENCES users (id),
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_proposal_rounds_semester_round UNIQUE (semester, round_number),
    CONSTRAINT chk_proposal_round_window CHECK (closes_at > opens_at)
);

-- ---------------------------------------------------------------- warning flags (bước 4.4)
CREATE TABLE warning_flags (
    id                  UUID PRIMARY KEY,
    group_id            UUID NOT NULL REFERENCES student_groups (id),
    member_id           UUID REFERENCES users (id),
    type                VARCHAR(30) NOT NULL CHECK (type IN ('GROUP_BEHIND_SCHEDULE', 'MEMBER_INACTIVE')),
    severity            VARCHAR(10) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    reason              TEXT NOT NULL,
    raised_by           UUID NOT NULL REFERENCES users (id),
    raised_at           TIMESTAMP NOT NULL,
    resolved_by         UUID REFERENCES users (id),
    resolved_at         TIMESTAMP,
    resolution_note     TEXT,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL
);

CREATE INDEX idx_warning_flags_group_resolved ON warning_flags (group_id, resolved_at);

-- ---------------------------------------------------------------- reviews 1-3 (Giai đoạn 5)
CREATE TABLE review_sessions (
    id                      UUID PRIMARY KEY,
    group_id                UUID NOT NULL REFERENCES student_groups (id),
    round                   VARCHAR(20) NOT NULL CHECK (round IN ('REVIEW_1', 'REVIEW_2', 'REVIEW_3')),
    scheduled_at            TIMESTAMP NOT NULL,
    duration_minutes        INT NOT NULL,
    location                VARCHAR(255) NOT NULL,
    completed_at            TIMESTAMP,
    feedback                TEXT,
    outcome                 VARCHAR(30)
                             CHECK (outcome IN ('READY_FOR_DEFENSE_1', 'REVISE_BEFORE_DEFENSE_1', 'DEFER_TO_DEFENSE_2')),
    revision_deadline       TIMESTAMP,
    revision_completed_at   TIMESTAMP,
    recorded_by             UUID REFERENCES users (id),
    created_at              TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP NOT NULL,
    CONSTRAINT uq_review_sessions_group_round UNIQUE (group_id, round)
);

CREATE INDEX idx_review_sessions_round_scheduled ON review_sessions (round, scheduled_at);

CREATE TABLE review_panel_members (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES review_sessions (id) ON DELETE CASCADE,
    reviewer_id     UUID NOT NULL REFERENCES users (id),
    chair           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_review_panel_session_reviewer UNIQUE (session_id, reviewer_id)
);

CREATE INDEX idx_review_panel_reviewer ON review_panel_members (reviewer_id);

-- ---------------------------------------------------------------- final defenses (Giai đoạn 6)
CREATE TABLE defense_sessions (
    id                  UUID PRIMARY KEY,
    group_id            UUID NOT NULL REFERENCES student_groups (id),
    attempt             INT NOT NULL CHECK (attempt IN (1, 2)),
    scheduled_at        TIMESTAMP NOT NULL,
    duration_minutes    INT NOT NULL,
    room                VARCHAR(100) NOT NULL,
    status              VARCHAR(20) NOT NULL CHECK (status IN ('SCHEDULED', 'PASSED', 'FAILED')),
    score               DOUBLE PRECISION CHECK (score BETWEEN 0 AND 10),
    feedback            TEXT,
    graded_by           UUID REFERENCES users (id),
    graded_at           TIMESTAMP,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    CONSTRAINT uq_defense_sessions_group_attempt UNIQUE (group_id, attempt)
);

CREATE INDEX idx_defense_sessions_scheduled ON defense_sessions (scheduled_at);

CREATE TABLE defense_committee_members (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES defense_sessions (id) ON DELETE CASCADE,
    member_id       UUID NOT NULL REFERENCES users (id),
    chair           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_defense_committee_session_member UNIQUE (session_id, member_id)
);

CREATE INDEX idx_defense_committee_member ON defense_committee_members (member_id);
