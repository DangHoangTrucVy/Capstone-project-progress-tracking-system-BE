-- Requirement v2.8 "Website ghép nhóm" (Team Formation Website).
--
-- The system now only forms student groups: semester -> subject (môn) -> formation scope (one major within one
-- subject) -> groups. The capstone-tracking tables (topics, proposals, slots, meetings, milestones, progress,
-- documents, reviews, defenses, warnings...) are out of scope and dropped. User accounts are kept; old groups and
-- requests are dropped with them because they have no subject or major to belong to.

-- ---------------------------------------------------------------- drop the out-of-scope domain
DROP TABLE IF EXISTS topic_proposal_items, topic_proposals, proposal_rounds,
    review_panel_members, review_sessions, defense_committee_members, defense_sessions,
    warning_flags, progress_tasks, weekly_progress_reports, artifact_submissions, milestones,
    evaluation_records, requirement_logs, meeting_minutes, meeting_sessions, bookings, schedule_slots,
    question_bank_items, roster_change_reports, member_leave_requests, group_application_votes,
    group_join_requests, semester_join_settings, semester_calendars, schedule_mutex CASCADE;

-- Notifications and queued events of the old domain point at dropped rows and use event types that no longer exist.
DELETE FROM notifications;
ALTER TABLE notifications DROP COLUMN group_id;
ALTER TABLE notifications ADD COLUMN group_id UUID;
DELETE FROM domain_event_outbox WHERE delivered_at IS NULL;

DROP TABLE IF EXISTS group_members, student_groups, topics CASCADE;

-- ---------------------------------------------------------------- users: ADMIN, LECTURER, STUDENT
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
UPDATE users SET role = 'LECTURER' WHERE role IN ('INSTRUCTOR', 'COUNCIL');
UPDATE users SET role = 'STUDENT' WHERE role = 'GROUP_LEADER';
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ADMIN', 'LECTURER', 'STUDENT'));

-- Self sign-up is gone (students sign in only when they are on an imported list).
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_status_check;
UPDATE users SET status = 'INACTIVE' WHERE status IN ('PENDING_APPROVAL', 'REJECTED');
ALTER TABLE users ADD CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'INACTIVE'));

DROP INDEX IF EXISTS idx_users_self_registered_status;
DROP INDEX IF EXISTS idx_users_imported_at;
ALTER TABLE users DROP COLUMN campus, DROP COLUMN eligible, DROP COLUMN ineligible_reason, DROP COLUMN bio,
    DROP COLUMN self_registered, DROP COLUMN rejection_reason, DROP COLUMN imported_at;

-- Profile (8.1): skills, strengths, weaknesses, phone, private mode.
ALTER TABLE users ALTER COLUMN skills TYPE VARCHAR(1000);
ALTER TABLE users ADD COLUMN phone VARCHAR(20);
ALTER TABLE users ADD COLUMN strengths VARCHAR(1000);
ALTER TABLE users ADD COLUMN weaknesses VARCHAR(1000);
ALTER TABLE users ADD COLUMN profile_private BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------- audit (11): free-form actions, per semester
ALTER TABLE system_audit_trail DROP CONSTRAINT IF EXISTS system_audit_trail_action_check;
ALTER TABLE system_audit_trail ALTER COLUMN action TYPE VARCHAR(40);
ALTER TABLE system_audit_trail ADD COLUMN semester_code VARCHAR(10);
-- Automatic transitions (opening, locking at the deadline) are done by the system: no user.
ALTER TABLE system_audit_trail ALTER COLUMN performed_by DROP NOT NULL;
CREATE INDEX idx_audit_semester ON system_audit_trail (semester_code, performed_at);

-- ---------------------------------------------------------------- semesters (3.1, 3.6)
-- Status (Upcoming / Ongoing / Completed) is computed from the dates; Archived is archived_at.
CREATE TABLE semesters (
    code                VARCHAR(10)  PRIMARY KEY CHECK (code ~ '^(SP|SU|FA)[0-9]{2}$'),
    name                VARCHAR(100) NOT NULL,
    start_date          DATE NOT NULL,
    end_date            DATE NOT NULL,
    formation_opens_at  TIMESTAMP,
    default_deadline    TIMESTAMP,
    moderator_id        UUID REFERENCES users (id),
    archived_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    CONSTRAINT chk_semesters_dates CHECK (end_date > start_date)
);

-- ---------------------------------------------------------------- majors (3.3)
CREATE TABLE majors (
    id          UUID PRIMARY KEY,
    code        VARCHAR(20)  NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL UNIQUE,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL
);

-- ---------------------------------------------------------------- the semester's student list (4.1)
CREATE TABLE semester_students (
    id              UUID PRIMARY KEY,
    semester_code   VARCHAR(10) NOT NULL REFERENCES semesters (code),
    user_id         UUID NOT NULL REFERENCES users (id),
    major_id        UUID NOT NULL REFERENCES majors (id),
    current_term    INT NOT NULL CHECK (current_term BETWEEN 1 AND 12),
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_semester_students UNIQUE (semester_code, user_id)
);
CREATE INDEX idx_semester_students_user ON semester_students (user_id);

-- ---------------------------------------------------------------- subjects (3.2)
CREATE TABLE subjects (
    id                  UUID PRIMARY KEY,
    semester_code       VARCHAR(10)  NOT NULL REFERENCES semesters (code),
    code                VARCHAR(20)  NOT NULL,
    name                VARCHAR(255) NOT NULL,
    target_term         INT NOT NULL CHECK (target_term BETWEEN 1 AND 12),
    min_group_size      INT NOT NULL DEFAULT 3,
    max_group_size      INT NOT NULL DEFAULT 5,
    request_ttl_hours   INT NOT NULL DEFAULT 48 CHECK (request_ttl_hours > 0),
    created_by          UUID NOT NULL REFERENCES users (id),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    CONSTRAINT uq_subjects_semester_code UNIQUE (semester_code, code),
    CONSTRAINT chk_subjects_sizes CHECK (min_group_size >= 1 AND max_group_size >= min_group_size)
);

-- ---------------------------------------------------------------- formation scopes: one major in one subject (3.4)
CREATE TABLE formation_scopes (
    id                          UUID PRIMARY KEY,
    subject_id                  UUID NOT NULL REFERENCES subjects (id),
    major_id                    UUID NOT NULL REFERENCES majors (id),
    phase                       VARCHAR(20) NOT NULL DEFAULT 'PREPARING'
                                 CHECK (phase IN ('PREPARING', 'OPEN', 'LOCKED', 'FINALIZED')),
    deadline_override           TIMESTAMP,
    opened_at                   TIMESTAMP,
    locked_at                   TIMESTAMP,
    finalized_at                TIMESTAMP,
    finalized_by                UUID REFERENCES users (id),
    previous_finalized_at       TIMESTAMP,
    reopened_at                 TIMESTAMP,
    reopened_by                 UUID REFERENCES users (id),
    reopen_reason               VARCHAR(1000),
    last_change_after_finalize  VARCHAR(500),
    last_change_after_finalize_at TIMESTAMP,
    open_blocked_notified_at    TIMESTAMP,
    created_at                  TIMESTAMP NOT NULL,
    updated_at                  TIMESTAMP NOT NULL,
    CONSTRAINT uq_formation_scopes UNIQUE (subject_id, major_id)
);
CREATE INDEX idx_formation_scopes_phase ON formation_scopes (phase);

-- ---------------------------------------------------------------- a student in a subject: the three-part status (5.1)
-- Group status (has a group or not) is computed from group_members; no column holds it.
CREATE TABLE enrollments (
    id                      UUID PRIMARY KEY,
    subject_id              UUID NOT NULL REFERENCES subjects (id),
    scope_id                UUID NOT NULL REFERENCES formation_scopes (id),
    semester_student_id     UUID NOT NULL REFERENCES semester_students (id),
    user_id                 UUID NOT NULL REFERENCES users (id),
    eligibility             VARCHAR(20) NOT NULL DEFAULT 'UNDECIDED'
                             CHECK (eligibility IN ('UNDECIDED', 'ELIGIBLE', 'INELIGIBLE')),
    eligibility_reason      VARCHAR(500),
    eligibility_updated_by  UUID REFERENCES users (id),
    eligibility_updated_at  TIMESTAMP,
    finalized               BOOLEAN NOT NULL DEFAULT FALSE,
    finalized_at            TIMESTAMP,
    no_group_reason         VARCHAR(30)
                             CHECK (no_group_reason IN ('GROUP_DISSOLVED', 'INELIGIBLE', 'REMOVED_BY_MODERATOR', 'LEFT_GROUP')),
    created_at              TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP NOT NULL,
    CONSTRAINT uq_enrollments_subject_user UNIQUE (subject_id, user_id)
);
CREATE INDEX idx_enrollments_scope ON enrollments (scope_id);
CREATE INDEX idx_enrollments_user ON enrollments (user_id);

-- ---------------------------------------------------------------- groups (6)
CREATE TABLE student_groups (
    id                  UUID PRIMARY KEY,
    scope_id            UUID NOT NULL REFERENCES formation_scopes (id),
    code                VARCHAR(40)  NOT NULL UNIQUE,
    name                VARCHAR(100) NOT NULL,
    wanted_skills       VARCHAR(500),
    locked              BOOLEAN NOT NULL DEFAULT FALSE,
    created_by          UUID NOT NULL REFERENCES users (id),
    dissolved_at        TIMESTAMP,
    dissolved_reason    VARCHAR(30) CHECK (dissolved_reason IN ('TOO_SMALL_AT_DEADLINE', 'EMPTY', 'MODERATOR')),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL
);
CREATE INDEX idx_student_groups_scope ON student_groups (scope_id) WHERE dissolved_at IS NULL;

CREATE TABLE group_members (
    id              UUID PRIMARY KEY,
    group_id        UUID NOT NULL REFERENCES student_groups (id),
    enrollment_id   UUID NOT NULL REFERENCES enrollments (id),
    user_id         UUID NOT NULL REFERENCES users (id),
    is_leader       BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at       TIMESTAMP NOT NULL,
    left_at         TIMESTAMP,
    left_reason     VARCHAR(30) CHECK (left_reason IN ('LEFT', 'KICKED', 'INELIGIBLE', 'MOVED', 'DISSOLVED',
                                                     'REMOVED_BY_MODERATOR', 'LEAVE_APPROVED')),
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);
-- One group per student per subject, and one Leader per group, even under concurrent requests (12).
CREATE UNIQUE INDEX uq_group_members_one_group ON group_members (enrollment_id) WHERE left_at IS NULL;
CREATE UNIQUE INDEX uq_group_members_one_leader ON group_members (group_id) WHERE is_leader AND left_at IS NULL;
CREATE INDEX idx_group_members_group ON group_members (group_id) WHERE left_at IS NULL;
CREATE INDEX idx_group_members_user ON group_members (user_id);

-- ---------------------------------------------------------------- Apply / Invite (6.2) and Likes (8.2)
CREATE TABLE join_requests (
    id              UUID PRIMARY KEY,
    group_id        UUID NOT NULL REFERENCES student_groups (id),
    enrollment_id   UUID NOT NULL REFERENCES enrollments (id),
    user_id         UUID NOT NULL REFERENCES users (id),
    type            VARCHAR(10) NOT NULL CHECK (type IN ('APPLY', 'INVITE')),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'CANCELLED')),
    message         VARCHAR(1000),
    reject_reason   VARCHAR(1000),
    cancel_reason   VARCHAR(30),
    created_by      UUID NOT NULL REFERENCES users (id),
    expires_at      TIMESTAMP NOT NULL,
    responded_at    TIMESTAMP,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uq_join_requests_pending ON join_requests (group_id, enrollment_id, type) WHERE status = 'PENDING';
CREATE INDEX idx_join_requests_enrollment ON join_requests (enrollment_id, status);
CREATE INDEX idx_join_requests_group ON join_requests (group_id, status);

CREATE TABLE application_likes (
    id          UUID PRIMARY KEY,
    request_id  UUID NOT NULL REFERENCES join_requests (id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users (id),
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    CONSTRAINT uq_application_likes UNIQUE (request_id, user_id)
);

-- ---------------------------------------------------------------- leave requests and complaints (6.4, 7.1)
-- PENDING_LEADER: member of a locked group asks the Leader. PENDING_MODERATOR: a complaint (Leader refused, the
-- request was pending at the deadline) or a request made after the deadline.
CREATE TABLE leave_requests (
    id                  UUID PRIMARY KEY,
    group_id            UUID NOT NULL REFERENCES student_groups (id),
    enrollment_id       UUID NOT NULL REFERENCES enrollments (id),
    user_id             UUID NOT NULL REFERENCES users (id),
    status              VARCHAR(20) NOT NULL
                         CHECK (status IN ('PENDING_LEADER', 'PENDING_MODERATOR', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CLOSED')),
    reason              VARCHAR(1000),
    complaint           BOOLEAN NOT NULL DEFAULT FALSE,
    leader_decided_by   UUID REFERENCES users (id),
    leader_decided_at   TIMESTAMP,
    leader_note         VARCHAR(1000),
    decided_by          UUID REFERENCES users (id),
    decided_at          TIMESTAMP,
    decision_note       VARCHAR(1000),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uq_leave_requests_open ON leave_requests (enrollment_id)
    WHERE status IN ('PENDING_LEADER', 'PENDING_MODERATOR');
CREATE INDEX idx_leave_requests_group ON leave_requests (group_id, status);

-- ---------------------------------------------------------------- Leader transfer (6.4): the recipient must accept
CREATE TABLE leader_transfers (
    id              UUID PRIMARY KEY,
    group_id        UUID NOT NULL REFERENCES student_groups (id),
    from_user_id    UUID NOT NULL REFERENCES users (id),
    to_user_id      UUID NOT NULL REFERENCES users (id),
    leave_after     BOOLEAN NOT NULL DEFAULT FALSE,
    after_finalize  BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    responded_at    TIMESTAMP,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uq_leader_transfers_pending ON leader_transfers (group_id) WHERE status = 'PENDING';

-- ---------------------------------------------------------------- import history (4.1, 4.2)
CREATE TABLE import_batches (
    id              UUID PRIMARY KEY,
    kind            VARCHAR(20) NOT NULL CHECK (kind IN ('STUDENTS', 'ELIGIBILITY', 'LECTURERS')),
    semester_code   VARCHAR(10) REFERENCES semesters (code),
    file_name       VARCHAR(255),
    imported_by     UUID NOT NULL REFERENCES users (id),
    imported_at     TIMESTAMP NOT NULL,
    created_count   INT NOT NULL DEFAULT 0,
    updated_count   INT NOT NULL DEFAULT 0,
    error_count     INT NOT NULL DEFAULT 0,
    errors_json     TEXT,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);
CREATE INDEX idx_import_batches_semester ON import_batches (semester_code, imported_at);

-- ---------------------------------------------------------------- email: one row per (event, recipient)
ALTER TABLE email_outbox DROP CONSTRAINT IF EXISTS email_outbox_event_id_key;
ALTER TABLE email_outbox ADD CONSTRAINT uq_email_outbox_event_recipient UNIQUE (event_id, recipients_to);
