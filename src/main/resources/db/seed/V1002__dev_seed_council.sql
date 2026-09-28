-- DEV-ONLY: a Council (Hội đồng) account for the topic-approval, closed-council and defense screens.
-- Password: Admin@123 (same hash as V2's admin). Campus left NULL: pinned on the first Google sign-in.
INSERT INTO users (id, email, full_name, password_hash, role, status, created_at, updated_at) VALUES
('20000000-0000-0000-0000-000000000010', 'hoidong@fpt.edu.vn', 'Hội đồng Thẩm định', '$2b$10$JhjE1P6m28aTN7z8KDcdVuPbff7YZl3UjwuF5PVWKa2ysTzJqrQy.', 'COUNCIL', 'ACTIVE', LOCALTIMESTAMP, LOCALTIMESTAMP);
