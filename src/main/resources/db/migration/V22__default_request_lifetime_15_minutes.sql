-- Change only the default for newly inserted configurations, preserving all existing values and requests.
ALTER TABLE subjects ALTER COLUMN request_ttl_minutes SET DEFAULT 15;
