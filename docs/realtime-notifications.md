# Realtime notification contract

`GET /api/notifications/stream` is an authenticated, fetch-based SSE stream. Clients send the
existing bearer JWT in the `Authorization` header; tokens are never accepted in query parameters.
Each connection receives `notifications.sync`, and durable notification commits may produce a
minimal `notifications.changed` invalidation. Clients obtain actual notification state from
`GET /api/notifications`.

Redis Pub/Sub and SSE are presentation-only, ephemeral delivery. PostgreSQL remains the durable
source of truth. `Last-Event-ID` is only a reconnect hint and does not provide replay or prove
ownership. Duplicate or missed invalidations are harmless.

Clients should reconnect with jittered backoff of roughly 1–30 seconds, refresh notifications
immediately after reconnect and on browser/window focus, and poll about every 60 seconds while the
stream is unavailable. Streams receive a comment heartbeat every 20 seconds and close after the
smaller of 10 minutes or the JWT's remaining validity.

The current limits are three streams per user per application instance and a configurable,
provisional 1,000 streams per instance. The latter is not a capacity claim and must be measured in
Phase 13. No sticky sessions are required. The 20-second heartbeat is below the current default ALB
idle timeout; if CloudFront is introduced, its SSE caching and buffering behavior must be handled in
Phase 10.
