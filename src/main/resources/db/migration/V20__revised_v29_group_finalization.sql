-- Revised requirements supplied 21/10/2026: finalization belongs to groups, not students.
-- Preserve former columns/tables for older dev seeds and historical data; they are no longer exposed or used.
ALTER TABLE student_groups ADD COLUMN finalized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE student_groups ADD COLUMN finalized_at TIMESTAMP;
UPDATE student_groups g SET finalized = TRUE, finalized_at = f.finalized_at
FROM formation_scopes f WHERE g.scope_id = f.id AND f.phase = 'FINALIZED' AND g.dissolved_at IS NULL
    AND (SELECT count(*) FROM group_members m WHERE m.group_id = g.id AND m.left_at IS NULL) BETWEEN 3 AND 5;
CREATE INDEX idx_group_finalized ON student_groups (scope_id, finalized) WHERE dissolved_at IS NULL;
-- Students authenticate through Google only; remove legacy password credentials.
UPDATE users SET password_hash = NULL WHERE role = 'STUDENT';
UPDATE enrollments SET finalized = FALSE, finalized_at = NULL;
UPDATE semesters SET archived_at = NULL;
