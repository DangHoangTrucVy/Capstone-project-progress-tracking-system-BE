-- FR-004.5: group documents are submitted per milestone ("theo từng mốc kiểm tra", blueprint §4) and can be an
-- uploaded file (stored by the backend) or a link (GitHub, demo, Drive...). Weekly progress reports feed the
-- group dashboard.

CREATE TABLE milestones (
    id              UUID PRIMARY KEY,
    code            VARCHAR(50)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    semester        VARCHAR(20)  NOT NULL,
    due_date        TIMESTAMP,
    sequence_no     INT NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_milestones_semester_code UNIQUE (semester, code)
);

-- Existing rows are all client-supplied links, hence the LINK default.
ALTER TABLE artifact_submissions ADD COLUMN milestone_id UUID REFERENCES milestones (id);
ALTER TABLE artifact_submissions ADD COLUMN source_type VARCHAR(10) NOT NULL DEFAULT 'LINK'
    CHECK (source_type IN ('FILE', 'LINK'));
ALTER TABLE artifact_submissions ADD COLUMN original_filename VARCHAR(255);
ALTER TABLE artifact_submissions ADD COLUMN content_type VARCHAR(100);
ALTER TABLE artifact_submissions ADD COLUMN size_bytes BIGINT;
ALTER TABLE artifact_submissions ADD COLUMN storage_key VARCHAR(500);
ALTER TABLE artifact_submissions ADD COLUMN submitted_by UUID REFERENCES users (id);
-- Uploaded files have no external URL; the API serves them from /api/v1/artifacts/{id}/file.
ALTER TABLE artifact_submissions ALTER COLUMN file_url DROP NOT NULL;

CREATE INDEX idx_artifact_submissions_group_milestone ON artifact_submissions (group_id, milestone_id);

CREATE TABLE weekly_progress_reports (
    id                    UUID PRIMARY KEY,
    group_id              UUID NOT NULL REFERENCES student_groups (id),
    week_number           INT  NOT NULL CHECK (week_number BETWEEN 1 AND 15),
    progress_percentage   INT  NOT NULL CHECK (progress_percentage BETWEEN 0 AND 100),
    summary               TEXT,
    blockers              TEXT,
    next_week_plan        TEXT,
    submitted_by          UUID NOT NULL REFERENCES users (id),
    instructor_feedback   TEXT,
    feedback_by           UUID REFERENCES users (id),
    feedback_at           TIMESTAMP,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    CONSTRAINT uq_weekly_progress_group_week UNIQUE (group_id, week_number)
);

CREATE TABLE progress_tasks (
    id              UUID PRIMARY KEY,
    report_id       UUID NOT NULL REFERENCES weekly_progress_reports (id) ON DELETE CASCADE,
    title           VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    assignee_id     UUID REFERENCES users (id),
    sort_order      INT NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_progress_tasks_report ON progress_tasks (report_id);
