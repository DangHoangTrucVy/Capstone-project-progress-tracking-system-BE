-- Seeds Council (Hội đồng) accounts so topic approval, Review 3 and final defenses can be exercised.
-- Login: council01..council05@fpt.edu.vn / Council@123 (password or Google sign-in with the same email).
-- Campus is left NULL so the first Google sign-in pins it. CHANGE THESE PASSWORDS in any non-local environment.
INSERT INTO users (id, email, full_name, password_hash, role, status, created_at, updated_at)
VALUES
    ('22222222-2222-2222-2222-000000000001', 'council01@fpt.edu.vn', 'Council Member 01',
     '$2a$10$2jpZ0Nb1NPJ0fl33ENtyZufGwKj/1AZ/u6jny9vwkaBVdTmF/py26', 'COUNCIL', 'ACTIVE', now(), now()),
    ('22222222-2222-2222-2222-000000000002', 'council02@fpt.edu.vn', 'Council Member 02',
     '$2a$10$2jpZ0Nb1NPJ0fl33ENtyZufGwKj/1AZ/u6jny9vwkaBVdTmF/py26', 'COUNCIL', 'ACTIVE', now(), now()),
    ('22222222-2222-2222-2222-000000000003', 'council03@fpt.edu.vn', 'Council Member 03',
     '$2a$10$2jpZ0Nb1NPJ0fl33ENtyZufGwKj/1AZ/u6jny9vwkaBVdTmF/py26', 'COUNCIL', 'ACTIVE', now(), now()),
    ('22222222-2222-2222-2222-000000000004', 'council04@fpt.edu.vn', 'Council Member 04',
     '$2a$10$2jpZ0Nb1NPJ0fl33ENtyZufGwKj/1AZ/u6jny9vwkaBVdTmF/py26', 'COUNCIL', 'ACTIVE', now(), now()),
    ('22222222-2222-2222-2222-000000000005', 'council05@fpt.edu.vn', 'Council Member 05',
     '$2a$10$2jpZ0Nb1NPJ0fl33ENtyZufGwKj/1AZ/u6jny9vwkaBVdTmF/py26', 'COUNCIL', 'ACTIVE', now(), now())
ON CONFLICT (email) DO NOTHING;
