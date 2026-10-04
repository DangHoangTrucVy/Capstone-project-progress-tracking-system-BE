-- Issue #45: Support CANCELLED status for meeting_sessions when booking is cancelled.
ALTER TABLE meeting_sessions DROP CONSTRAINT IF EXISTS meeting_sessions_session_status_check;
ALTER TABLE meeting_sessions ADD CONSTRAINT meeting_sessions_session_status_check
    CHECK (session_status IN ('SCHEDULED', 'IN_PROGRESS', 'CONCLUDED', 'CANCELLED'));
