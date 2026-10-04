-- Students without a school email sign up with a personal email + student code (MSSV) and wait for an Admin to
-- confirm they are a student of the school before they can sign in.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_status_check;
ALTER TABLE users ADD CONSTRAINT users_status_check
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'INACTIVE', 'PENDING_APPROVAL', 'REJECTED'));

ALTER TABLE users ADD COLUMN student_code VARCHAR(20);
ALTER TABLE users ADD CONSTRAINT uq_users_student_code UNIQUE (student_code);
ALTER TABLE users ADD COLUMN self_registered BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN rejection_reason VARCHAR(500);

CREATE INDEX idx_users_self_registered_status ON users (self_registered, status);
