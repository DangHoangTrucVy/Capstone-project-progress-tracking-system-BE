-- v2.9: Google only, imported lecturer provenance, one Capstone configuration per semester.
-- Existing lecturers must be imported again to establish provenance. Do not infer it from a role.
ALTER TABLE users ADD COLUMN lecturer_imported BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD CONSTRAINT chk_imported_lecturer CHECK (NOT lecturer_imported OR role = 'LECTURER');

-- Refuse incompatible legacy data instead of deleting students, groups or history silently.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM subjects WHERE code <> 'CAP' OR name <> 'Capstone Project')
       OR EXISTS (SELECT semester_code FROM subjects GROUP BY semester_code HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'v2.9 supports only one Capstone Project (CAP) per semester. Reconcile legacy subjects before retrying migration; no data was deleted.';
    END IF;
END $$;

ALTER TABLE subjects ADD CONSTRAINT uq_capstone_semester UNIQUE (semester_code);
ALTER TABLE subjects ADD CONSTRAINT chk_capstone_only CHECK (code = 'CAP' AND name = 'Capstone Project');
ALTER TABLE subjects ADD CONSTRAINT chk_capstone_group_sizes CHECK (min_group_size = 3 AND max_group_size = 5);

INSERT INTO subjects (id, semester_code, code, name, target_term, min_group_size, max_group_size,
                      request_ttl_hours, created_by, created_at, updated_at)
SELECT gen_random_uuid(), s.code, 'CAP', 'Capstone Project', 8, 3, 5, 48,
       (SELECT id FROM users WHERE role = 'ADMIN' ORDER BY created_at, id LIMIT 1), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM semesters s WHERE NOT EXISTS (SELECT 1 FROM subjects c WHERE c.semester_code = s.code);

INSERT INTO formation_scopes (id, subject_id, major_id, phase, created_at, updated_at)
SELECT gen_random_uuid(), c.id, ss.major_id, 'PREPARING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM subjects c JOIN semester_students ss ON ss.semester_code = c.semester_code AND ss.current_term = c.target_term
WHERE NOT EXISTS (SELECT 1 FROM formation_scopes f WHERE f.subject_id = c.id AND f.major_id = ss.major_id)
GROUP BY c.id, ss.major_id;

INSERT INTO enrollments (id, subject_id, scope_id, semester_student_id, user_id, eligibility, finalized, created_at, updated_at)
SELECT gen_random_uuid(), c.id, f.id, ss.id, ss.user_id, 'UNDECIDED', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM subjects c JOIN semester_students ss ON ss.semester_code = c.semester_code AND ss.current_term = c.target_term
JOIN formation_scopes f ON f.subject_id = c.id AND f.major_id = ss.major_id
WHERE NOT EXISTS (SELECT 1 FROM enrollments e WHERE e.subject_id = c.id AND e.user_id = ss.user_id);
