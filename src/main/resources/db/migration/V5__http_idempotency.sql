-- V5: HTTP idempotency records for reservation creation.
--
-- The raw Idempotency-Key is never stored. key_hash and request_fingerprint are SHA-256 digests.
-- An IN_PROGRESS row exists only inside the transaction that owns execution; successful execution,
-- its response snapshot, and the reservation commit together as one unit.

CREATE TABLE idempotency_records (
    id                   uuid         NOT NULL,
    user_id              uuid         NOT NULL,
    key_hash             bytea        NOT NULL,
    request_fingerprint  bytea        NOT NULL,
    state                varchar(20)  NOT NULL,
    response_status      smallint,
    response_body        jsonb,
    response_location    varchar(500),
    created_at           timestamptz  NOT NULL,
    completed_at         timestamptz,
    expires_at           timestamptz,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    CONSTRAINT fk_idempotency_records_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_idempotency_records_user_key UNIQUE (user_id, key_hash),
    CONSTRAINT ck_idempotency_records_key_hash
        CHECK (octet_length(key_hash) = 32),
    CONSTRAINT ck_idempotency_records_fingerprint
        CHECK (octet_length(request_fingerprint) = 32),
    CONSTRAINT ck_idempotency_records_state
        CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_records_state_response CHECK (
        (state = 'IN_PROGRESS'
            AND response_status IS NULL
            AND response_body IS NULL
            AND response_location IS NULL
            AND completed_at IS NULL
            AND expires_at IS NULL)
        OR
        (state = 'COMPLETED'
            AND response_status BETWEEN 200 AND 299
            AND response_body IS NOT NULL
            AND jsonb_typeof(response_body) = 'object'
            AND response_location IS NOT NULL
            AND btrim(response_location) <> ''
            AND completed_at IS NOT NULL
            AND completed_at >= created_at
            AND expires_at IS NOT NULL
            AND expires_at > completed_at)
    )
);

-- Bounded cleanup scans completed records in expiry order. Expiration has no request-time
-- semantics: a row continues to replay or conflict until cleanup physically deletes it.
CREATE INDEX ix_idempotency_records_completed_expiry
    ON idempotency_records (expires_at, id)
    WHERE state = 'COMPLETED';
