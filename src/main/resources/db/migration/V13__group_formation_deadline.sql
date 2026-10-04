-- YC17 / YC21: students form groups, change groups and ask to leave only "within the permitted time". The Admin sets
-- that cut-off per semester; NULL means no cut-off. After it, roster changes go through the supervisor and the Admin.
ALTER TABLE semester_join_settings ADD COLUMN formation_deadline TIMESTAMP;
