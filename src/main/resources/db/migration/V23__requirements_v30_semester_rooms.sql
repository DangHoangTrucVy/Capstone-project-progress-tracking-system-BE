-- Requirement v3.0 (08/11/2026): the semester is the central entity.
--
-- * The semester holds the phase (OPENING -> FORMING -> SUPPLEMENT -> CLOSED), the one common deadline and the rules
--   (max group size, default minimum room size, pending requests per student, request lifetime). Round 1 and round 2
--   finalization are Moderator actions recorded on the semester, not phases.
-- * The Capstone "subject" row and the per-major phase/deadline are gone. formation_scopes stays as "one major within
--   one semester" (rooms and students of one major), now keyed by semester_code.
-- * A room and a group are the same row (student_groups): the Moderator creates rooms ahead (no code), students join
--   freely, the first one in is the Leader, and the group code <semester>-<major>-<nnn> is issued when the Moderator
--   approves the room (or when formation ends). An emptied room is kept (back to empty), not dissolved.

-- ---------------------------------------------------------------- semesters: phase, deadline, rules
ALTER TABLE semesters RENAME COLUMN default_deadline TO deadline;
ALTER TABLE semesters ADD COLUMN phase VARCHAR(20) NOT NULL DEFAULT 'OPENING'
    CHECK (phase IN ('OPENING', 'FORMING', 'SUPPLEMENT', 'CLOSED'));
ALTER TABLE semesters ADD COLUMN max_group_size INT NOT NULL DEFAULT 5;
ALTER TABLE semesters ADD COLUMN default_min_room_size INT NOT NULL DEFAULT 3;
ALTER TABLE semesters ADD COLUMN max_pending_requests INT NOT NULL DEFAULT 3;
ALTER TABLE semesters ADD COLUMN request_ttl_minutes INT NOT NULL DEFAULT 2880;
ALTER TABLE semesters ADD COLUMN forming_started_at TIMESTAMP;
ALTER TABLE semesters ADD COLUMN rooms_locked_at TIMESTAMP;
ALTER TABLE semesters ADD COLUMN round1_finalized_at TIMESTAMP;
ALTER TABLE semesters ADD COLUMN round1_finalized_by UUID REFERENCES users (id);
ALTER TABLE semesters ADD COLUMN round2_finalized_at TIMESTAMP;
ALTER TABLE semesters ADD COLUMN round2_finalized_by UUID REFERENCES users (id);
ALTER TABLE semesters ADD COLUMN closed_at TIMESTAMP;
ALTER TABLE semesters ADD CONSTRAINT chk_semester_rules CHECK (
    default_min_room_size >= 1 AND max_group_size >= default_min_room_size AND max_group_size <= 20
    AND max_pending_requests BETWEEN 1 AND 20 AND request_ttl_minutes BETWEEN 1 AND 20160);

UPDATE semesters s SET request_ttl_minutes = c.request_ttl_minutes FROM subjects c WHERE c.semester_code = s.code;

-- The semester's phase from the old per-major phases: all finalized -> CLOSED, any past its deadline -> FORMING with
-- the rooms locked, any open -> FORMING, otherwise OPENING.
UPDATE semesters s SET phase = 'CLOSED', closed_at = now() AT TIME ZONE 'UTC'
WHERE EXISTS (SELECT 1 FROM formation_scopes f JOIN subjects c ON c.id = f.subject_id WHERE c.semester_code = s.code)
  AND NOT EXISTS (SELECT 1 FROM formation_scopes f JOIN subjects c ON c.id = f.subject_id
                  WHERE c.semester_code = s.code AND f.phase <> 'FINALIZED');
UPDATE semesters s SET phase = 'FORMING', forming_started_at = now() AT TIME ZONE 'UTC',
                       rooms_locked_at = now() AT TIME ZONE 'UTC'
WHERE s.phase = 'OPENING' AND EXISTS (SELECT 1 FROM formation_scopes f JOIN subjects c ON c.id = f.subject_id
                                      WHERE c.semester_code = s.code AND f.phase IN ('LOCKED', 'FINALIZED'));
UPDATE semesters s SET phase = 'FORMING', forming_started_at = now() AT TIME ZONE 'UTC'
WHERE s.phase = 'OPENING' AND EXISTS (SELECT 1 FROM formation_scopes f JOIN subjects c ON c.id = f.subject_id
                                      WHERE c.semester_code = s.code AND f.phase = 'OPEN');

-- ---------------------------------------------------------------- formation_scopes: one major within one semester
ALTER TABLE formation_scopes ADD COLUMN semester_code VARCHAR(10) REFERENCES semesters (code);
UPDATE formation_scopes f SET semester_code = c.semester_code FROM subjects c WHERE c.id = f.subject_id;
ALTER TABLE formation_scopes ALTER COLUMN semester_code SET NOT NULL;
ALTER TABLE formation_scopes DROP CONSTRAINT uq_formation_scopes;
ALTER TABLE formation_scopes DROP COLUMN subject_id, DROP COLUMN phase, DROP COLUMN deadline_override,
    DROP COLUMN opened_at, DROP COLUMN locked_at, DROP COLUMN finalized_at, DROP COLUMN finalized_by,
    DROP COLUMN previous_finalized_at, DROP COLUMN reopened_at, DROP COLUMN reopened_by, DROP COLUMN reopen_reason,
    DROP COLUMN last_change_after_finalize, DROP COLUMN last_change_after_finalize_at,
    DROP COLUMN open_blocked_notified_at;
-- Last room number (Room 01, Room 02...) and last group number issued in the major; numbers are never reused.
ALTER TABLE formation_scopes ADD COLUMN room_seq INT NOT NULL DEFAULT 0;
ALTER TABLE formation_scopes ADD COLUMN group_seq INT NOT NULL DEFAULT 0;
ALTER TABLE formation_scopes ADD CONSTRAINT uq_formation_scopes_semester_major UNIQUE (semester_code, major_id);

-- ---------------------------------------------------------------- enrollments: one per student of the semester
ALTER TABLE enrollments DROP CONSTRAINT uq_enrollments_subject_user;
ALTER TABLE enrollments DROP COLUMN subject_id, DROP COLUMN finalized, DROP COLUMN finalized_at;
ALTER TABLE enrollments ADD CONSTRAINT uq_enrollments_semester_student UNIQUE (semester_student_id);

-- ---------------------------------------------------------------- rooms (student_groups)
ALTER TABLE student_groups ALTER COLUMN code DROP NOT NULL;
ALTER TABLE student_groups ADD COLUMN room_no INT;
ALTER TABLE student_groups ADD COLUMN min_size INT NOT NULL DEFAULT 3 CHECK (min_size >= 1);
ALTER TABLE student_groups ADD COLUMN finalize_status VARCHAR(20) NOT NULL DEFAULT 'NONE'
    CHECK (finalize_status IN ('NONE', 'REQUESTED', 'APPROVED', 'RETURNED'));
ALTER TABLE student_groups ADD COLUMN finalize_requested_at TIMESTAMP;
ALTER TABLE student_groups ADD COLUMN finalize_requested_by UUID REFERENCES users (id);
ALTER TABLE student_groups ADD COLUMN finalize_decided_at TIMESTAMP;
ALTER TABLE student_groups ADD COLUMN finalize_decided_by UUID REFERENCES users (id);
ALTER TABLE student_groups ADD COLUMN finalize_note VARCHAR(1000);
ALTER TABLE student_groups DROP CONSTRAINT IF EXISTS student_groups_dissolved_reason_check;
ALTER TABLE student_groups ADD CONSTRAINT student_groups_dissolved_reason_check
    CHECK (dissolved_reason IN ('TOO_SMALL_AT_DEADLINE', 'EMPTY', 'MODERATOR', 'CLOSED_EMPTY'));

-- Existing groups: finalized ones keep a code in the new format, the others are rooms without a code yet.
UPDATE student_groups SET finalize_status = 'APPROVED', finalize_decided_at = finalized_at,
                          code = regexp_replace(code, '-CAP-', '-')
WHERE finalized AND dissolved_at IS NULL;
UPDATE student_groups SET code = NULL WHERE NOT finalized OR dissolved_at IS NOT NULL;
UPDATE student_groups g SET room_no = n.rn
FROM (SELECT id, row_number() OVER (PARTITION BY scope_id ORDER BY created_at, id) AS rn FROM student_groups) n
WHERE n.id = g.id;
UPDATE formation_scopes f SET room_seq = COALESCE((SELECT max(room_no) FROM student_groups g WHERE g.scope_id = f.id), 0),
                              group_seq = COALESCE((SELECT max(substring(g.code FROM '([0-9]+)$')::int)
                                                    FROM student_groups g WHERE g.scope_id = f.id AND g.code IS NOT NULL), 0);

-- ---------------------------------------------------------------- invites of the supplement phase never expire
ALTER TABLE join_requests ALTER COLUMN expires_at DROP NOT NULL;

-- ---------------------------------------------------------------- the subject row is gone
DROP TABLE subjects CASCADE;
