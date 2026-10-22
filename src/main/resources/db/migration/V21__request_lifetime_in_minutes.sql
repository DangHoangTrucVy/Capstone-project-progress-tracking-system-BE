-- Preserve existing settings and historical migrations; new APIs use whole minutes.
ALTER TABLE subjects ADD COLUMN request_ttl_minutes INTEGER NOT NULL DEFAULT 2880;
UPDATE subjects SET request_ttl_minutes = request_ttl_hours * 60;
ALTER TABLE subjects ADD CONSTRAINT ck_subject_request_ttl_minutes
    CHECK (request_ttl_minutes BETWEEN 1 AND 20160);
