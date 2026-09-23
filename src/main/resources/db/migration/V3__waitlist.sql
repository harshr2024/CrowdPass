-- V3: Waitlist.
--
-- queue_seq is a stable ordering token assigned by the database, not a stored position. Gaps
-- (e.g. from rolled-back joins) are expected. A user's position is derived at read time.

CREATE TABLE waitlist_entries (
    id              uuid        NOT NULL,
    event_id        uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    queue_seq       bigint      GENERATED ALWAYS AS IDENTITY,
    status          varchar(20) NOT NULL,
    reservation_id  uuid,
    created_at      timestamptz NOT NULL,
    promoted_at     timestamptz,
    left_at         timestamptz,

    CONSTRAINT pk_waitlist_entries PRIMARY KEY (id),
    -- IDENTITY alone does not guarantee uniqueness; queue_seq defines a total order.
    CONSTRAINT uq_waitlist_entries_queue_seq UNIQUE (queue_seq),
    CONSTRAINT fk_waitlist_entries_event FOREIGN KEY (event_id)
        REFERENCES events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_waitlist_entries_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_waitlist_entries_status CHECK (status IN ('WAITING', 'PROMOTED', 'LEFT')),
    CONSTRAINT ck_waitlist_entries_state_fields CHECK (
        (status = 'WAITING'  AND reservation_id IS NULL     AND promoted_at IS NULL     AND left_at IS NULL) OR
        (status = 'PROMOTED' AND reservation_id IS NOT NULL AND promoted_at IS NOT NULL AND left_at IS NULL) OR
        (status = 'LEFT'     AND reservation_id IS NULL     AND promoted_at IS NULL     AND left_at IS NOT NULL)),
    CONSTRAINT ck_waitlist_entries_promoted_after_created
        CHECK (promoted_at IS NULL OR promoted_at >= created_at),
    CONSTRAINT ck_waitlist_entries_left_after_created
        CHECK (left_at IS NULL OR left_at >= created_at)
);

-- Target for the composite foreign key below.
ALTER TABLE reservations
    ADD CONSTRAINT uq_reservations_id_event_user UNIQUE (id, event_id, user_id);

-- A promoted entry's reservation must belong to the same event and user.
ALTER TABLE waitlist_entries
    ADD CONSTRAINT fk_waitlist_entries_reservation FOREIGN KEY (reservation_id, event_id, user_id)
        REFERENCES reservations (id, event_id, user_id) ON DELETE RESTRICT;

-- One reservation fulfils at most one waitlist entry.
CREATE UNIQUE INDEX ux_waitlist_entries_promoted_reservation
    ON waitlist_entries (reservation_id)
    WHERE reservation_id IS NOT NULL;

-- At most one WAITING entry per user per event; history rows are unrestricted.
CREATE UNIQUE INDEX ux_waitlist_entries_waiting_user_event
    ON waitlist_entries (event_id, user_id)
    WHERE status = 'WAITING';

-- Head of the queue (ORDER BY queue_seq LIMIT 1) and position counts.
CREATE INDEX ix_waitlist_entries_waiting_order
    ON waitlist_entries (event_id, queue_seq)
    WHERE status = 'WAITING';

-- A user's latest entry for an event, in any status.
CREATE INDEX ix_waitlist_entries_user_event_seq
    ON waitlist_entries (user_id, event_id, queue_seq);
