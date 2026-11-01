-- V2000 is immutable historical demo data; erase its former student password hashes after loading it.
UPDATE users SET password_hash = NULL WHERE role = 'STUDENT';
