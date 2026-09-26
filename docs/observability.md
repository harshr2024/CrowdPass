# Observability and operator guide

CrowdPass uses Spring Boot Actuator and Micrometer. Signals are intentionally small and
low-cardinality; PostgreSQL remains the place to verify durable business state.

## Runtime surfaces

- Every HTTP response receives `X-Request-Id`; the same bounded value is placed in logging MDC and
  structured `ApiError` responses.
- `/livez` means the process is alive. `/readyz` includes PostgreSQL because authoritative requests
  cannot be served without it. Redis and SQS are deliberately excluded from readiness.
- Default Actuator exposure is only `health` and `info`. The `observability` profile additionally
  exposes `metrics` and Prometheus format, but Spring Security requires an ADMIN bearer token.
- JVM, HTTP server, Hikari and process metrics come from Actuator. Application metrics cover the
  correctness-sensitive and asynchronous flows below.

| Metric | Meaning and bounded labels |
|---|---|
| `crowdpass.reservations.operations` | Timer/count by `operation=reserve|cancel` and finite `outcome` |
| `crowdpass.waitlist.operations` | Timer/count by `operation=join|leave` and finite `outcome` |
| `crowdpass.waitlist.promotions` | Promotions committed in the cancellation transaction |
| `crowdpass.idempotency.requests` | First execution, replay, key conflict or owner rollback |
| `crowdpass.outbox.publish` | SQS publish success/failure per event |
| `crowdpass.outbox.pending` | Current unpublished row count |
| `crowdpass.outbox.oldest_pending_age_seconds` | Age of the oldest unpublished event |
| `crowdpass.notifications.consumed` | Created, duplicate or failed consumption with finite reason |
| `crowdpass.notifications.ack_failures` | Delete-after-processing failures; redelivery is expected |
| `crowdpass.ratelimit.decisions` | Allowed, limited or failed-open by fixed policy name |
| `crowdpass.ratelimit.redis.latency` | Redis decision latency |
| `crowdpass.realtime.connections` | Active instance-local SSE connections |
| `crowdpass.realtime.*` | Bounded connection, publication, delivery, drop and subscriber outcomes |

Successful reservation/waitlist metrics are registered after transaction commit. An idempotency
replay therefore increments the replay counter but not another confirmed-reservation result.
Metric labels never contain user/event/reservation IDs, email, IP, JWT subject, request ID or an
idempotency key.

## Local demonstration

Start dependencies and run with both local configuration and the explicit metrics profile:

```sh
docker compose up -d postgres redis elasticmq
SPRING_PROFILES_ACTIVE=local,observability ./mvnw spring-boot:run
```

Exercise the complete reservation, full-event, waitlist, promotion and idempotency flow with the
focused integration tests (they use disposable real PostgreSQL and Redis containers):

```sh
./mvnw -Dtest=ReservationServiceIntegrationTest,WaitlistServiceIntegrationTest,IdempotencyIntegrationTest test
```

The focused tests are the recommended reproducible demonstration: they create disposable fixtures,
exercise the outcomes, and assert the resulting meters. There is intentionally no public ADMIN
bootstrap endpoint. If a disposable local user has been assigned the ADMIN role through local
database administration, log in as that user and query the running process:

```sh
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/prometheus
```

Anonymous access returns 401, USER access returns 403, and the default profile returns 404 even for
an ADMIN because the operational endpoints are not exposed. No Prometheus server or Grafana stack is
required to demonstrate the instrumentation.

## Investigation order

1. Check `/livez` and `/readyz`; a failed readiness probe makes PostgreSQL the first dependency to inspect.
2. Correlate an API error and logs with `X-Request-Id` without searching for identities or tokens.
3. Inspect HTTP/domain outcome rates and Hikari pending/active connections.
4. For notification delay, inspect outbox pending count, oldest age, publish failures, consumer
   failures and the SQS DLQ in that order.
5. Treat realtime failures as invalidation loss: clients refetch durable notifications.

## Proposed production SLI/SLO targets

These are design targets for a future production service, not achieved SLAs or historical uptime.

- API server-error ratio: target <0.1% over 30 days, excluding bounded 4xx domain outcomes.
- non-streaming API latency: target p95 <250 ms over five-minute windows.
- reservation mutation latency: target p95 <500 ms while correctness invariants remain non-negotiable.
- readiness availability: target 99.9% monthly once a continuously operated deployment exists.
- outbox oldest pending age: target <60 seconds; page if >5 minutes.
- notification consumer failures: target <0.1% excluding duplicates; any sustained rise is actionable.
- Redis rate-limit fail-open: normally zero; alert on sustained non-zero rate.
- SSE: track active connections, delivery failures and dropped signals; do not treat ephemeral signal
  loss as durable notification loss.

Alert thresholds require calibration with real production traffic; the local Phase 13 measurements
are not that calibration.
