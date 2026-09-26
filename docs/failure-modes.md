# Failure modes and recovery contracts

## PostgreSQL unavailable

`/readyz` becomes DOWN and authoritative mutations are unavailable. `/livez` can remain UP. No
fallback datastore is allowed to allocate seats or alter waitlist state.

## Redis unavailable

Rate limiting fails open after short timeouts and records a bounded metric/rate-limited warning.
Reservation correctness remains PostgreSQL-backed. Realtime Pub/Sub may miss invalidations, but SSE
clients recover by refetching durable notifications. Redis failure does not fail readiness.

## SQS unavailable

The domain transaction and its outbox row remain committed. Publish failures retain the row with
backoff; pending count and oldest-pending age grow until publishing recovers. Delivery is
at-least-once, never exactly-once.

## Duplicate SQS delivery

The consumer inserts with a PostgreSQL uniqueness constraint on `source_event_id`. A duplicate is
acknowledged without producing a second durable notification. If acknowledgement fails after commit,
redelivery is safe for the same reason.

## Crash after domain commit

The async intent was inserted into the transactional outbox in the same PostgreSQL transaction. A
later publisher instance finds and sends it. A crash before commit preserves neither domain change
nor outbox row.

## SSE disconnect or Redis signal loss

Streams have finite JWT/lifetime bounds and heartbeats. Clients reconnect with backoff and fetch
`GET /api/notifications`; `Last-Event-ID` is only an observability hint, not replay. PostgreSQL
notifications—not SSE events—are durable.

## Hot-event contention

Seat-changing transactions serialize on the event row. The conditional PostgreSQL update
re-evaluates capacity after lock acquisition, so committed confirmations cannot exceed capacity.
Higher contention can increase latency; weakening this lock is not an acceptable latency fix.

## Same-key HTTP retry storm

The unique `(user_id, key_hash)` claim selects one transaction owner. Other matching requests wait
for commit and replay the stored response. Coordination latency is expected; different request
fingerprints receive a stable conflict. Raw keys are never stored or logged.

## Operator cautions

Do not delete pending outbox or idempotency rows as a first response, increase Hikari without pool
evidence, or use Redis/SQS state to repair seat capacity. Investigate PostgreSQL constraints and
transactional state first.
