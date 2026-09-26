# Phase 13A local baseline

Measured 2026-09-25 with the reproducible harness in the parent directory. These are local
developer-laptop results, not production capacity claims.

## Environment and method

- Apple M3 MacBook Air, 8 cores (4 performance/4 efficiency), 16 GB RAM; macOS 26.6.2.
- Docker Desktop 28.3.0, Linux ARM64 VM configured with 8 CPUs and 7.65 GiB memory.
- CrowdPass Java 21 production image; PostgreSQL 17, Redis 8.10.2, ElasticMQ 1.7.1.
- k6 1.3.0 pinned by image digest. The load generator shared the same Docker Desktop VM.
- Baseline Spring Boot/Hikari defaults: maximum pool 10; no pool or application tuning.
- Local-only root logging `WARN`; Redis remained in the request path. Local rate-limit ceilings
  were raised for core measurements, and a separate five-request abuse smoke produced two 429s.
- Deterministic SQL fixtures and generated `example.invalid` users. Password hashing and parallel
  JWT login occurred before measured intervals.
- Warmup: 30 seconds at 20 public-read requests/second (600 requests, zero failures) after readiness.
- Open-model constant arrival rate except the finite, closed-loop same-key retry storm.

All recorded runs had zero unexpected responses, server failures, client failures, timeouts, and
dropped iterations. Latencies are end-to-end HTTP `http_req_duration` values.

## Recorded measurements

| Scenario | Load and duration | Dataset | Requests | req/s | p50 ms | p95 ms | p99 ms | Expected business results |
|---|---:|---|---:|---:|---:|---:|---:|---:|
| Public reads | 25/s, 20s | 100 events | 500 | 25.00 | 3.52 | 7.24 | 9.37 | 0 |
| Public reads | 100/s, 20s | 100 events | 2,001 | 100.03 | 1.71 | 4.61 | 27.60 | 0 |
| Public reads | 250/s, 20s | 100 events | 5,001 | 250.00 | 0.73 | 2.47 | 3.74 | 0 |
| Public reads | 500/s, 20s | 100 events | 10,002 | 500.01 | 0.61 | 1.79 | 3.27 | 0 |
| Public reads | 1,000/s, 20s | 100 events | 20,001 | 1,000.00 | 0.57 | 1.39 | 4.45 | 0 |
| Public reads | 2,000/s, 20s | 100 events | 40,003 | 1,999.95 | 0.44 | 1.10 | 4.14 | 0 |
| Public reads | 5,000/s, 20s | 100 events | 100,000 | 4,999.72 | 0.41 | 1.83 | 7.45 | 0 |
| Sufficient-capacity reservations | 10/s, 10s | 100 users; 100 seats | 100 | 10.00 | 11.73 | 17.06 | 19.50 | 0 |
| Sufficient-capacity reservations | 50/s, 10s | 550 users/seats | 500 | 49.99 | 3.02 | 6.43 | 8.13 | 0 |
| Sufficient-capacity reservations | 100/s, 10s | 1,100 users/seats | 1,001 | 100.05 | 1.79 | 4.23 | 5.97 | 0 |
| Sufficient-capacity reservations | 200/s, 10s | 2,200 users/seats | 2,004 | 199.70 | 1.29 | 3.18 | 4.08 | 0 |
| Sufficient-capacity reservations | 500/s, 10s | 5,500 users/seats | 5,001 | 499.94 | 0.86 | 1.83 | 4.25 | 0 |
| Sufficient-capacity reservations | 1,000/s, 5s | 5,500 users/seats | 5,001 | 999.66 | 0.61 | 1.99 | 8.37 | 0 |
| Hot event | 100/s, 5s | 500 users; 100 seats | 500 | 99.97 | 1.81 | 4.59 | 7.23 | 400 `EVENT_FULL` |
| FIFO waitlist | 100/s, 5s | 501 waiters; event prefilled to 100 | 501 | 100.10 | 2.14 | 5.13 | 13.57 | 0 |
| Same-key idempotency storm | 100 VUs, 500 iterations | 1 user/key; 1,000 seats | 500 | 3,663.02 | 12.70 | 63.40 | 88.46 | 0 |
| Distinct-key idempotency | 100/s, 5s | 550 users/seats | 501 | 100.13 | 2.10 | 4.76 | 6.46 | 0 |
| Synthetic mixed workload | 100/s, 60s | 100 events; 550 users; 500 seats | 6,001 | 100.01 | 2.26 | 4.10 | 6.01 | 0 |

The synthetic mix was exactly 70% event lists, 15% event details, 10% authenticated notification
reads, and 5% reservations. It is a CrowdPass test mix, not a claim about real ticketing traffic.

## Correctness and resource evidence

- Sufficient-capacity runs: confirmed rows equaled `events.reserved_count` and recorded request
  count, stayed within capacity, and had no duplicate confirmed user/event pair.
- Hot event: exactly 100 confirmed, `reserved_count=100`, no oversell, and 400 expected full-event
  outcomes.
- Waitlist: 501 `WAITING` rows, 501 distinct `queue_seq` values in database order, no duplicate
  active membership, and no user simultaneously confirmed and waiting. Sequence assignment is the
  FIFO ordering token; no wall-clock arrival-order claim is made.
- Same-key storm: one confirmed reservation, one capacity unit, one completed idempotency row, and
  its stored response ID matched the reservation. All 500 HTTP responses succeeded.
- Distinct-key run: 501 confirmed reservations and 501 completed, response-matching idempotency rows.
- Mixed run: exactly 300 confirmed reservations and `reserved_count=300`, with no impossible state.

One-second-intent sampling used `docker stats`, `pg_stat_activity`, and authenticated Actuator
Hikari metrics. Docker/HTTP command overhead made actual samples roughly every 2–3 seconds. At the
5,000/s read run, peak app CPU was 144.36% and RSS 741.9 MiB; PostgreSQL active sessions and Hikari
active connections peaked at 2, with Hikari pending 0 and maximum 10. At the 1,000/s reservation
run, peak app CPU was 41.76%, RSS 780.3 MiB, PostgreSQL active 1, Hikari active 1, pending 0. Across
the sampled mutation and 60-second mixed runs, pending remained 0 and PostgreSQL active peaked at 2.

No steady-state saturation knee was observed at the bounded maximums tested. The first visible
pressure signal was application CPU on the 5,000/s read run, while neither the Hikari pool nor
PostgreSQL sessions showed saturation. Same-key retry coordination intentionally created the
largest tail latency (p95 63.40 ms) but no duplicate work or errors. The load generator also showed
no dropped iterations or client errors; because it shared the Docker VM, higher local numbers would
increasingly confound client and server resource use.

## Limitations and next evidence

- Short mutation runs reduce confidence in long-duration tail behavior; the 1,000/s run was a
  five-second probe. Public-read latency improved after early runs as JVM/database caches warmed.
- Container CPU/RSS is not JVM heap telemetry, and sampling is coarse. PostgreSQL CPU and row-lock
  wait time were not directly sampled.
- The local load generator and server shared one machine and Docker VM. No production or AWS
  throughput inference is valid.
- A Phase 13B review should rank: (1) add targeted PostgreSQL lock-wait and JVM heap/GC sampling,
  (2) run longer mutation plateaus before changing any pool, and (3) only if evidence then shows a
  ceiling, separate the load generator and evaluate query/transaction profiles. Hikari resizing is
  not justified by this baseline because pending connections remained zero.

Regression after harness implementation: 457 tests, 0 failures, 0 errors, 0 skipped on Java 21.
