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

# Phase 13B steady-state validation

Measured 2026-09-25/26 on the same local M3/Docker environment as Phase 13A. This section preserves
the original baseline above and adds longer, better-instrumented runs; it does not replace it. These
remain same-host laptop measurements, not production or AWS capacity claims.

## Method

- One application JVM stayed running throughout. A deliberate 60-second, 1,000/s read warmup ran
  first, followed by an unrecorded 10-second warmup before every retained 30-second plateau.
- The initial warmup completed 59,947 requests at 999.1/s but dropped 54 iterations because its
  initial k6 VU allocation was too low. Warmup allocation was then raised; every retained run had
  zero dropped iterations. Warmup output is not included in retained results.
- Mutation fixtures were reset between levels. A separate event received a 10-second, 100/s
  reservation warmup, so measured-event capacity remained untouched. The 16,000 deterministic-user
  token set was generated before timing and reused; database correctness was checked after each run.
- k6 and CrowdPass still shared the Docker Desktop VM. Longer plateaus reduce transient bias but do
  not separate load-generator and server CPU or establish production capacity.

## Retained 30-second plateaus

All runs below had zero unexpected responses, HTTP failures, timeouts and dropped iterations.

| Scenario | Offered rate | Requests | Achieved req/s | p50 ms | p95 ms | p99 ms | Max ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| Public reads, 100 events | 500/s | 15,001 | 499.99 | 0.52 | 1.33 | 2.59 | 23.89 |
| Public reads, 100 events | 1,000/s | 30,001 | 1,000.01 | 0.45 | 1.19 | 3.01 | 34.87 |
| Public reads, 100 events | 2,000/s | 60,000 | 1,999.93 | 0.41 | 0.99 | 2.57 | 42.28 |
| Public reads, 100 events | 5,000/s | 149,999 | 4,998.52 | 0.44 | 1.68 | 6.17 | 103.18 |
| Reservations, sufficient capacity | 100/s | 3,001 | 100.02 | 1.59 | 4.12 | 6.18 | 19.07 |
| Reservations, sufficient capacity | 250/s | 7,501 | 250.02 | 0.99 | 2.82 | 5.31 | 101.31 |
| Reservations, sufficient capacity | 500/s | 15,001 | 499.98 | 0.83 | 3.09 | 27.41 | 132.66 |

For every reservation plateau, confirmed rows exactly equaled both `events.reserved_count` and the
completed request count; capacity was not exceeded; no user/event duplicate existed; and no user
was both confirmed and waiting. A 1,000/s 30-second mutation plateau was not pursued: the stable
500/s key plateau already answered the pool/locking question, while the next run would require more
than 30,000 pre-created identities merely to chase a larger local number.

## JVM and PostgreSQL evidence

| Run | Peak app CPU | Peak RSS MiB | Heap start/end MiB | Peak/committed heap MiB | GC count/time delta | Peak threads | Hikari active/pending | PG active | Lock-wait samples / peak ungranted | Max active tx ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Reads 500/s | 39.59% | 667.0 | 93.06 / 124.66 | 136.64 / 168 | 22 / 0.049s | 63 | 1 / 0 | 1 | 0 / 0 | 0.39 |
| Reads 1,000/s | 42.34% | 682.2 | 85.89 / 98.33 | 130.28 / 168 | 51 / 0.104s | 68 | 1 / 0 | 1 | 0 / 0 | 0.17 |
| Reads 2,000/s | 60.31% | 731.0 | 170.79 / 135.77 | 175.27 / 204 | 76 / 0.111s | 118 | 1 / 0 | 2 | 0 / 0 | 0.25 |
| Reads 5,000/s | 147.82% | 844.2 | 143.07 / 215.74 | 255.66 / 360 | 121 / 0.198s | 226 | 5 / 0 | 2 | 0 / 0 | 0.35 |
| Reservations 100/s | 26.76% | 925.9 | 144.19 / 272.27 | 280.19 / 360 | 1 / 0.005s | 42 | 0 / 0 | 1 | 0 / 0 | 0.30 |
| Reservations 250/s | 20.60% | 935.2 | 184.43 / 164.49 | 288.41 / 360 | 4 / 0.029s | 56 | 1 / 0 | 1 | 0 / 0 | 0.57 |
| Reservations 500/s | 32.89% | 936.6 | 140.37 / 113.13 | 280.88 / 360 | 8 / 0.032s | 85 | 2 / 0 | 4 | 5 / 3 | 8.57 |

CPU is the first repeatable pressure signal: the 5,000/s read plateau used about 1.48 host CPU
cores at the peak and had the highest read tail. GC consumed only 0.198 seconds across that
30-second plateau, heap remained bounded, and Hikari had no pending acquisition. The increasing
thread count at high read load followed request concurrency but produced neither errors nor pool
queueing. RSS stayed elevated after earlier levels because this was one deliberately warmed JVM;
within-plateau heap samples do not show monotonic growth.

At 500 reservation requests/s, brief event-row serialization became observable (five sampled
lock-wait intervals, at most three ungranted locks, and an 8.57 ms maximum active transaction), but
Hikari used only 2 of 10 connections with none pending. This is healthy bounded contention for the
authoritative atomic event-row update, not connection-pool saturation.

## Stronger correctness and idempotency contention

- **Hot event:** exactly 1,000 distinct authenticated attempts competed for 100 seats using 200 VUs.
  The finite run completed in 0.44 seconds: 100 reservations were confirmed, 900 responses were the
  expected `EVENT_FULL`, `reserved_count` was exactly 100, and there were no duplicates, impossible
  states, unexpected 5xx responses or timeouts. Overall latency was p50 60.39 ms, p95 247.70 ms and
  p99 310.97 ms; successful mutations were p95 79.14 ms.
- **Same-key idempotency:** three independent clean-fixture runs each sent 1,000 requests with 200
  VUs. All 3,000 responses succeeded. Every run produced exactly one confirmed reservation, one
  capacity unit and one completed idempotency record whose response ID matched that reservation.
  The three p50 values were 33.12, 25.97 and 13.25 ms; p95 values were 149.12, 45.90 and 30.46 ms;
  p99 values were 203.56, 57.09 and 36.93 ms. One sub-second burst sample observed all 10 Hikari
  connections active and 102 callers pending; PostgreSQL sampling observed no sustained lock wait.
  The variation and bounded tail are consistent with cold-to-warm initialization plus intentional
  unique-key transaction coordination: concurrent `INSERT ... ON CONFLICT` claims wait for the one
  owner transaction to commit, then read its durable response. The test is shorter than sampler
  cadence, so it is evidence of bounded burst coordination, not a steady-state pool-sizing signal.

## Query plans and tuning decision

Local `EXPLAIN (ANALYZE, BUFFERS)` on 100 events showed the public list and count using sequential
scans over all 100 qualifying rows, with execution times of 0.091 ms and 0.026 ms. The list's
top-N sort used 33 KiB. For this tiny, all-qualifying fixture, a sequential scan is appropriate and
does not justify another index. The atomic seat update used `pk_events` by event ID and completed in
0.202 ms. No pathological row count, sort, or mutation plan was found.

Phase 13 produced no evidence supporting a production-code or configuration optimization. Hikari
remains at 10 because retained plateaus had zero pending acquisitions; increasing it could add
database contention without addressing the observed read-path CPU pressure. No cache, index,
database control, lock, production code or production configuration was changed. The first local
pressure signal is application/request-processing CPU at 5,000 read requests/s, but the same-host
k6/Docker setup cannot separate server work, JSON/HTTP processing and load-generator contention
well enough to justify a targeted production change.

Defensible claims from these measurements are deliberately narrow: CrowdPass prevented overselling
when 1,000 concurrent local attempts competed for 100 seats, and preserved one durable effect under
each of three 1,000-request same-key retry storms. Locally, a warmed JVM sustained the retained read
and reservation plateaus above with no unexpected errors, while CPU—not PostgreSQL connections—was
the first observed pressure signal. Remaining limitations include one laptop/shared Docker VM,
coarse sampling of sub-second waits, a small read dataset, no separate load-generator host, no
production traffic model, and no basis for cloud or production throughput extrapolation.
