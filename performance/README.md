# CrowdPass local performance harness

This directory contains a reproducible, laptop-scale k6 baseline harness. It exercises the real
Spring Boot application, PostgreSQL 17, Redis rate limiting, ElasticMQ messaging, JWT
authentication, database constraints, and reservation/waitlist locking. Results are local evidence,
not production capacity claims.

## Prerequisites and isolation

- Docker Desktop with Compose v2 and enough resources for the stack
- `curl`, `jq`, Python 3, and OpenSSL on the host
- ports 8080, 5432, 6379, and 9324 available

The scripts use the dedicated Compose project `crowdpass-performance` and disposable volumes.
Generated passwords, BCrypt-derived fixture material, and JWTs live only in ignored
`performance/.runtime/` files with mode 0600. Raw k6 output and runtime samples are ignored.
Never use personal identities or production credentials.

k6 runs in the pinned container
`grafana/k6:1.3.0@sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d`;
no host installation or paid service is required.

## Reproducible run

```sh
performance/scripts/validate.sh
performance/scripts/start.sh
performance/scripts/prepare-fixture.sh public-reads 1 100 100
performance/scripts/prepare-tokens.sh 1
performance/scripts/warmup.sh
RATE=100 DURATION=20s performance/scripts/run-scenario.sh public-reads reads-100
performance/scripts/stop.sh
```

Fixture preparation truncates only the dedicated benchmark database, creates generated
`example.invalid` users with one BCrypt hash obtained through the real registration path, inserts
deterministic future published events with SQL, and performs parallel logins before timing starts.
It must never be pointed at a non-disposable database.

For mutating runs, reset the fixture and prepare at least one token per logical user, then verify:

```sh
# Sufficient capacity: RATE * seconds users/capacity, then expected completed iterations.
performance/scripts/prepare-fixture.sh reservations 500 500
performance/scripts/prepare-tokens.sh 500
RATE=50 DURATION=10s performance/scripts/run-scenario.sh reservations reservations-50
performance/scripts/verify.sh reservations 500

# 500 users compete for 100 seats.
performance/scripts/prepare-fixture.sh hot-event 500 100
performance/scripts/prepare-tokens.sh 500
RATE=100 DURATION=5s performance/scripts/run-scenario.sh hot-event hot-100
performance/scripts/verify.sh hot-event

# A pre-filled event admits 500 distinct waiters.
performance/scripts/prepare-fixture.sh waitlist 500 100
performance/scripts/prepare-tokens.sh 500
RATE=50 DURATION=10s performance/scripts/run-scenario.sh waitlist waitlist-50
performance/scripts/verify.sh waitlist 500

# Same user/key retry storm, followed by normal distinct-key overhead.
performance/scripts/prepare-fixture.sh idempotency-storm 1 1000
performance/scripts/prepare-tokens.sh 1
VUS=100 ITERATIONS=500 performance/scripts/run-scenario.sh idempotency-storm storm-500
performance/scripts/verify.sh idempotency-storm

performance/scripts/prepare-fixture.sh idempotency-distinct 500 500
performance/scripts/prepare-tokens.sh 500
RATE=50 DURATION=10s performance/scripts/run-scenario.sh idempotency-distinct distinct-50
performance/scripts/verify.sh idempotency-distinct 500

# Synthetic mix: 70% event list, 15% detail, 10% notification reads, 5% reservations.
performance/scripts/prepare-fixture.sh mixed 500 500 100
performance/scripts/prepare-tokens.sh 500
RATE=100 DURATION=60s performance/scripts/run-scenario.sh mixed mixed-100
performance/scripts/verify.sh mixed 300
```

Use a 30-second, 20 requests/second read warmup before recorded runs. Public reads and mutations
use open-model constant arrival rates so server queueing appears as latency or dropped iterations,
instead of being hidden by closed-loop pacing. Run low, moderate, high, and near-saturation levels;
stop before the host becomes unusable. The retry storm intentionally uses closed-loop shared
iterations because it models a finite simultaneous retry set.

For comparable steady-state plateaus, keep one JVM running, perform one deliberate read warmup,
then run a short unrecorded warmup immediately before each retained level. Reservation warmup uses
a dedicated sufficient-capacity event so it cannot consume seats from the measured event:

```sh
# One-time JVM/cache warmup, then a per-level read warmup and 30-second retained plateau.
RATE=1000 DURATION=60s PRE_ALLOCATED_VUS=1200 MAX_VUS=4000 performance/scripts/warmup.sh
RATE=2000 DURATION=10s PRE_ALLOCATED_VUS=1200 MAX_VUS=4000 performance/scripts/warmup.sh
RATE=2000 DURATION=30s PRE_ALLOCATED_VUS=1200 MAX_VUS=4000 \
  performance/scripts/run-scenario.sh public-reads reads-2000

# Create two events, prepare enough users/tokens, warm the mutation path separately, then measure.
performance/scripts/prepare-fixture.sh reservations 16000 16000 2
performance/scripts/prepare-tokens.sh 16000
RATE=100 DURATION=10s PRE_ALLOCATED_VUS=100 MAX_VUS=1000 \
  performance/scripts/warmup-reservations.sh
RATE=500 DURATION=30s PRE_ALLOCATED_VUS=300 MAX_VUS=1200 \
  performance/scripts/run-scenario.sh reservations reservations-500
performance/scripts/verify.sh reservations 15001
```

The same warmed JVM is intentional: these plateaus compare steady-state behavior, not cold starts.
Reset mutation fixtures between levels, but reuse deterministic-user JWTs generated before timing.

Each run writes a k6 summary and console output to `results/raw/`. It also samples app container
CPU/RSS; JVM heap, GC and thread metrics; Hikari active/pending/max connections; and PostgreSQL
sessions, wait categories, ungranted locks and active-transaction age into `results/runtime/`.
Sampling is deliberately lightweight and may miss sub-second waits. These machine-specific raw
files are ignored. `summarize.py` emits a sanitized k6 row, while `summarize-runtime.py <run-name>`
summarizes the two runtime CSVs. `explain-plans.sh` captures safe local `EXPLAIN (ANALYZE, BUFFERS)`
output for the public list/count and atomic seat update into an ignored file.

## Scenarios and failure classification

- `public-reads`: alternating public list/detail requests.
- `reservations`: one sufficient-capacity reservation per distinct user.
- `hot-event`: excess distinct users compete for limited seats; `EVENT_FULL` is an expected business
  result. Set `ITERATIONS` and `VUS` for an exact finite contention set.
- `waitlist`: distinct users join an already-full event; PostgreSQL sequence values define FIFO tokens,
  not guaranteed wall-clock arrival order.
- `idempotency-storm`: same authenticated user, payload, and key concurrently; one logical reservation.
- `idempotency-distinct`: distinct users and keys measure normal idempotency overhead.
- `mixed`: explicitly synthetic 70/15/10/5 percent list/detail/notification/reservation traffic.

Custom k6 counters separate expected business results, unexpected responses, server failures,
client/load-generator failures, and timeouts. The core profile keeps Redis in the request path but
raises only local policy ceilings to avoid measuring the limiter. To prove abuse protection still
activates, prepare a `reservations` fixture and run `performance/scripts/rate-limit-smoke.sh`; restart
the normal performance stack afterward. This smoke is not a throughput benchmark.

The app retains the baseline Hikari configuration (Spring Boot defaults; no benchmark pool tuning).
Root logging is reduced to WARN only in the local benchmark overlay, without changing privacy
behavior. The harness does not disable persistence, locks, constraints, messaging, or rate limiting.

## Interpreting results

Always report hardware/container resources, duration, arrival rate/VUs, dataset, request count,
throughput, p50/p95/p99, errors and expected business outcomes, correctness verification, warmup,
and configuration. A dropped iteration means the generator could not start work at the requested
rate and must not be hidden. Do not generalize laptop measurements to production or compare a cold
run directly with warmed steady state.
