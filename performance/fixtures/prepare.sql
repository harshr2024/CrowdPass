\set ON_ERROR_STOP on

TRUNCATE TABLE
    idempotency_records,
    notifications,
    outbox_events,
    waitlist_entries,
    reservations,
    events,
    users
RESTART IDENTITY CASCADE;

INSERT INTO users (id, email, password_hash, display_name, role, created_at, updated_at)
VALUES (
    '01960000-0000-7000-8000-000000000001',
    'perf-organizer@example.invalid',
    :'password_hash',
    'Performance Organizer',
    'ORGANIZER',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO users (id, email, password_hash, display_name, role, created_at, updated_at)
SELECT
    md5('crowdpass-performance-user-' || i)::uuid,
    'perf-user-' || lpad(i::text, 6, '0') || '@example.invalid',
    :'password_hash',
    'Performance User ' || i,
    'USER',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM generate_series(1, :user_count) AS generated(i);

INSERT INTO events (
    id, organizer_id, name, description, capacity, reserved_count, status, time_zone,
    registration_open_at, registration_close_at, starts_at, ends_at, cancelled_at,
    created_at, updated_at
)
SELECT
    CASE WHEN i = 1 THEN :'event_id'::uuid ELSE md5('crowdpass-performance-event-' || :'scenario' || '-' || i)::uuid END,
    '01960000-0000-7000-8000-000000000001',
    'Performance ' || :'scenario' || ' event ' || i,
    'Disposable local performance fixture',
    :capacity,
    CASE WHEN :'scenario' = 'waitlist' THEN :capacity ELSE 0 END,
    'PUBLISHED',
    'UTC',
    CURRENT_TIMESTAMP - INTERVAL '1 hour',
    CURRENT_TIMESTAMP + INTERVAL '1 day',
    CURRENT_TIMESTAMP + INTERVAL '2 days',
    CURRENT_TIMESTAMP + INTERVAL '2 days 2 hours',
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM generate_series(1, :event_count) AS generated(i);

-- The waitlist workload begins full. Dedicated prefill users avoid consuming any measured user's
-- one-active-reservation slot.
INSERT INTO users (id, email, password_hash, display_name, role, created_at, updated_at)
SELECT
    md5('crowdpass-performance-prefill-' || i)::uuid,
    'perf-prefill-' || lpad(i::text, 6, '0') || '@example.invalid',
    :'password_hash',
    'Performance Prefill ' || i,
    'USER',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM generate_series(1, CASE WHEN :'scenario' = 'waitlist' THEN :capacity ELSE 0 END) AS generated(i);
INSERT INTO reservations (id, event_id, user_id, status, created_at, cancelled_at)
SELECT
    md5('crowdpass-performance-prefill-reservation-' || i)::uuid,
    :'event_id'::uuid,
    md5('crowdpass-performance-prefill-' || i)::uuid,
    'CONFIRMED',
    CURRENT_TIMESTAMP,
    NULL
FROM generate_series(1, CASE WHEN :'scenario' = 'waitlist' THEN :capacity ELSE 0 END) AS generated(i);
