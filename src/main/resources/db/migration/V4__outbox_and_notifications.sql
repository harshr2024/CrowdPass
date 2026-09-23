-- V4: Transactional outbox and in-app notifications.
--
-- Guarantees: a committed domain change has its outbox event committed in the same transaction;
-- a rolled-back change has none. Publication is at-least-once, so duplicates are possible, and
-- consumers are idempotent through uq_notifications_source_event. Nothing here is exactly-once.

CREATE TABLE outbox_events (
    id               uuid         NOT NULL,   -- UUIDv7; the stable message id on every (re)send
    event_type       varchar(100) NOT NULL,
    schema_version   integer      NOT NULL,
    aggregate_type   varchar(50)  NOT NULL,
    aggregate_id     uuid         NOT NULL,
    data             jsonb        NOT NULL,   -- event-specific body only; identifiers, no PII
    occurred_at      timestamptz  NOT NULL,
    published_at     timestamptz,
    attempts         integer      NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL,
    last_error       varchar(300),            -- error class/code only; never message content

    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_events_schema_version CHECK (schema_version >= 1),
    CONSTRAINT ck_outbox_events_data_object CHECK (jsonb_typeof(data) = 'object'),
    CONSTRAINT ck_outbox_events_published_after_occurred
        CHECK (published_at IS NULL OR published_at >= occurred_at)
);

-- The publisher's work queue: unpublished rows, earliest due first.
CREATE INDEX ix_outbox_events_pending
    ON outbox_events (next_attempt_at, id)
    WHERE published_at IS NULL;

CREATE TABLE notifications (
    id               uuid        NOT NULL,    -- UUIDv7
    source_event_id  uuid        NOT NULL,    -- outbox event id: the consumer idempotency key
    user_id          uuid        NOT NULL,
    type             varchar(50) NOT NULL,
    event_id         uuid        NOT NULL,    -- the CrowdPass event
    -- Required while WAITLIST_PROMOTED is the only type; loosen in a future migration if needed.
    reservation_id   uuid        NOT NULL,
    occurred_at      timestamptz NOT NULL,    -- when the domain change happened
    created_at       timestamptz NOT NULL,    -- when the consumer stored it
    read_at          timestamptz,

    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT uq_notifications_source_event UNIQUE (source_event_id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_notifications_event FOREIGN KEY (event_id)
        REFERENCES events (id) ON DELETE RESTRICT,
    -- The reservation must belong to this user and this CrowdPass event.
    CONSTRAINT fk_notifications_reservation FOREIGN KEY (reservation_id, event_id, user_id)
        REFERENCES reservations (id, event_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_notifications_type CHECK (type IN ('WAITLIST_PROMOTED')),
    CONSTRAINT ck_notifications_read_after_created CHECK (read_at IS NULL OR read_at >= created_at)
);

-- Owner-scoped listing, newest first (the query orders occurred_at DESC, id DESC).
CREATE INDEX ix_notifications_user_occurred
    ON notifications (user_id, occurred_at DESC, id DESC);
