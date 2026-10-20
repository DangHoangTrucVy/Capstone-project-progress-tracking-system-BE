-- Explicit fixture provenance. Production lecturers are verified by importing their list.
UPDATE users SET lecturer_imported = TRUE
WHERE role = 'LECTURER' AND id IN ('71000000-0000-0000-0000-000000000001',
    '71000000-0000-0000-0000-000000000002', '71000000-0000-0000-0000-000000000003');

INSERT INTO subjects (id, semester_code, code, name, target_term, created_by, created_at, updated_at)
SELECT '75000000-0000-0000-0000-000000000002', 'SP27', 'CAP', 'Capstone Project', 8,
    (SELECT id FROM users WHERE role = 'ADMIN' ORDER BY created_at, id LIMIT 1), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM subjects WHERE semester_code = 'SP27');
