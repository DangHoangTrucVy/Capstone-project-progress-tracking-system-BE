-- A Leader's request to leave the group goes to the Admins (not to the Leader themself); they decide it.
ALTER TABLE member_leave_requests ADD COLUMN leader_request BOOLEAN NOT NULL DEFAULT FALSE;

-- When a student account was last created/updated by the training department's list import (YC03), so the Admin
-- can list the imported students and not only read the counts.
ALTER TABLE users ADD COLUMN imported_at TIMESTAMP;
CREATE INDEX idx_users_imported_at ON users (imported_at);
