-- Disposable local demonstration data. Safe to run repeatedly against the Compose database only.
INSERT INTO users (id, email, password_hash, display_name, role, created_at, updated_at)
VALUES
  ('019b0000-0000-7000-8000-000000000001', 'demo-organizer@example.invalid', '{bcrypt}$2y$10$smVVMi1SNF3LHkXWyFi5R.6fPE3Sx97s6o6Vbyqp1YDjkaeneL0wy', 'CrowdPass Studio', 'ORGANIZER', now(), now()),
  ('019b0000-0000-7000-8000-000000000002', 'demo-holder@example.invalid', '{bcrypt}$2y$10$smVVMi1SNF3LHkXWyFi5R.6fPE3Sx97s6o6Vbyqp1YDjkaeneL0wy', 'Demo Holder', 'USER', now(), now())
ON CONFLICT (id) DO UPDATE
SET email = excluded.email,
    password_hash = excluded.password_hash,
    display_name = excluded.display_name,
    role = excluded.role,
    updated_at = now();

INSERT INTO events (
  id, organizer_id, name, description, capacity, reserved_count, status, time_zone,
  registration_open_at, registration_close_at, starts_at, ends_at, created_at, updated_at
)
VALUES
  ('019b0000-0000-7000-8000-000000000010', '019b0000-0000-7000-8000-000000000001',
   'Designing for the Rush', 'An evening on concurrency, fairness, and building systems that stay calm under pressure.',
   120, 0, 'PUBLISHED', 'America/Phoenix', now() - interval '1 day', now() + interval '20 days',
   now() + interval '21 days', now() + interval '21 days 3 hours', now(), now()),
  ('019b0000-0000-7000-8000-000000000011', '019b0000-0000-7000-8000-000000000001',
   'Midnight Sessions', 'A small-room live set where every seat matters and the waitlist moves fairly.',
   1, 1, 'PUBLISHED', 'America/Los_Angeles', now() - interval '1 day', now() + interval '13 days',
   now() + interval '14 days', now() + interval '14 days 2 hours', now(), now()),
  ('019b0000-0000-7000-8000-000000000012', '019b0000-0000-7000-8000-000000000001',
   'Future of Independent Events', 'Builders and organizers compare practical ways to create more trustworthy access.',
   80, 0, 'PUBLISHED', 'America/New_York', now() - interval '1 day', now() + interval '29 days',
   now() + interval '30 days', now() + interval '30 days 4 hours', now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO reservations (id, event_id, user_id, status, created_at)
VALUES ('019b0000-0000-7000-8000-000000000020', '019b0000-0000-7000-8000-000000000011',
        '019b0000-0000-7000-8000-000000000002', 'CONFIRMED', now())
ON CONFLICT (id) DO NOTHING;
