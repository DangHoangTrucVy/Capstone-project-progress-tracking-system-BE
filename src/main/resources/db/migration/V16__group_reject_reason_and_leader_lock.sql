-- The Leader's reason for rejecting an application, forwarded to the applicant.
ALTER TABLE group_join_requests ADD COLUMN reject_reason VARCHAR(1000);

-- A Leader may lock (finalize) their own roster and unlock it; a lock put by an Admin only an Admin lifts.
ALTER TABLE student_groups ADD COLUMN locked_by_admin BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE student_groups SET locked_by_admin = TRUE WHERE locked = TRUE;
