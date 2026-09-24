\set ON_ERROR_STOP on

-- Phase 10C disposable smoke-test fixture only. Never add this to Flyway or production startup.
-- It is intentionally deterministic so the API runbook can refer to the event ID.
INSERT INTO users (
    id, email, password_hash, display_name, role, created_at, updated_at
) VALUES (
    '01950000-0000-7000-8000-000000000001',
    'phase10-organizer@example.invalid',
    'not-a-login-capable-password-hash',
    'Phase 10 Fixture Organizer',
    'ORGANIZER',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

INSERT INTO events (
    id, organizer_id, name, description, capacity, reserved_count, status, time_zone,
    registration_open_at, registration_close_at, starts_at, ends_at, cancelled_at,
    created_at, updated_at
) VALUES (
    '01950000-0000-7000-8000-000000000010',
    '01950000-0000-7000-8000-000000000001',
    'Phase 10 AWS Smoke Event',
    'Disposable Phase 10C verification fixture',
    1,
    0,
    'PUBLISHED',
    'America/Phoenix',
    CURRENT_TIMESTAMP - INTERVAL '1 hour',
    CURRENT_TIMESTAMP + INTERVAL '1 day',
    CURRENT_TIMESTAMP + INTERVAL '2 days',
    CURRENT_TIMESTAMP + INTERVAL '2 days 2 hours',
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;
