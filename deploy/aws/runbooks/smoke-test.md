# ECS Exec smoke-test workflow

Prepared for Phase 10C only; do not execute during Phase 10B. The task has no inbound rules, so all
HTTP calls target `127.0.0.1:8080` inside the application container. ECS Exec commands run as root,
even though the Java process remains UID/GID `10001:10001`.

## 1. Establish the private session

Before starting the paid smoke-test window, verify that the operator workstation has AWS CLI v2
and the Session Manager plugin required by `aws ecs execute-command`. This is a workstation
prerequisite only; do not install either tool into the application image.

Resolve the ECS task ID in an ephemeral local variable and start an interactive ECS Exec shell in
the `crowdpass-api` container. Never paste the resulting session URL, task ARN, or account ID into
Git or screenshots. Disable shell history inside the session.

The AWS CLI invocation to use in Phase 10C has this shape (placeholders are resolved locally):

```bash
aws ecs execute-command \
  --region us-west-2 \
  --cluster crowdpass-temporary-demo \
  --task "$TASK_ARN" \
  --container crowdpass-api \
  --interactive \
  --command "/bin/sh"
```

Inside the session:

```sh
unset HISTFILE
curl --fail --silent --show-error http://127.0.0.1:8080/livez
curl --fail --silent --show-error http://127.0.0.1:8080/readyz
id
```

Expected: both probes are `UP`; `id` reports root for the ECS Exec command. Separately use the ECS
task/container metadata and process listing to prove the Java process runs as UID 10001. Do not
misrepresent the Exec shell's root identity as the application's identity.

## 2. Install disposable smoke tooling and seed the event

The Temurin runtime intentionally contains no database client. Because this temporary task already
has a writable root solely for ECS Exec, install `postgresql-client` and `jq` into the running
container. This mutation is ephemeral and disappears when the task is destroyed; it is not added to
the image. The ARM64 Temurin/Ubuntu image's package sources default to HTTP, while the approved task
security group deliberately permits outbound web traffic only on TCP 443. Rewrite only those
ephemeral package-source URLs to HTTPS before updating; do not add an outbound TCP 80 rule.

```sh
sed -i 's|URIs: http://ports.ubuntu.com/|URIs: https://ports.ubuntu.com/|' \
  /etc/apt/sources.list.d/ubuntu.sources
grep '^URIs:' /etc/apt/sources.list.d/ubuntu.sources
apt-get update
apt-get install --yes --no-install-recommends postgresql-client jq
rm -rf /var/lib/apt/lists/*
```

Expected: every printed package-source URI begins with `https://`. Stop if any HTTP source remains.

On the operator workstation, Base64-encode `../fixtures/seed-event.sql` without printing or
modifying it. Use ECS Exec to decode it to `/tmp/crowdpass-phase10-fixture.sql`. Then, inside the
private session:

```sh
PGPASSWORD="$DB_PASSWORD" psql "${DB_URL#jdbc:}" \
  --username "$DB_USERNAME" \
  --set ON_ERROR_STOP=on \
  --file /tmp/crowdpass-phase10-fixture.sql
```

The fixed event ID is `01950000-0000-7000-8000-000000000010`, capacity one. The fixture is
explicitly demo-only and must never become a Flyway migration or production startup seed.

## 3. Registration, login, reservation, and waitlist

Generate two unique `example.invalid` email addresses and one strong disposable password inside the
session. Keep the password and bearer tokens in shell variables; do not echo them or include them in
screenshots.

1. Register attendee A and attendee B through `POST /api/auth/register`.
2. Log both in through `POST /api/auth/login`; use `jq -r .accessToken` to capture tokens without
   displaying them.
3. Attendee A calls `POST /api/events/<event-id>/reservations` with a random `Idempotency-Key` and
   receives `CONFIRMED`. Capture the reservation ID in a variable.
4. Replay the exact request with the same idempotency key and verify the response identifies the
   same reservation.
5. Attendee B calls `POST /api/events/<event-id>/waitlist` and receives `WAITING`, position one.

Use only localhost URLs. An illustrative request shape is:

```sh
curl --fail --silent --show-error \
  --request POST \
  --header "Authorization: Bearer $TOKEN_A" \
  --header "Idempotency-Key: $IDEMPOTENCY_KEY" \
  "http://127.0.0.1:8080/api/events/$EVENT_ID/reservations"
```

## 4. Promotion, real SQS, notification, and SSE

1. Before cancellation, open attendee B's `/api/notifications/stream` with `curl -N` in the private
   session and write its body to `/tmp/crowdpass-sse.txt`; never display the command containing the
   token.
2. Attendee A cancels the confirmed reservation.
3. Poll attendee B's waitlist status until it is `PROMOTED` and has a reservation ID.
4. Poll `GET /api/notifications` until a `WAITLIST_PROMOTED` notification appears. This proves the
   PostgreSQL transaction produced an outbox row, the real Standard SQS queue accepted it, and the
   idempotent consumer durably recorded it.
5. Inspect only the sanitized SSE body and confirm an invalidation/sync event caused the client to
   refetch. Do not claim SSE itself delivered the durable notification.
6. Use a separate ECS Exec session targeting container `redis` and run `redis-cli ping`. Confirm
   `PONG`; do not expose port 6379.
7. In CloudWatch, confirm the expected outbox/consumer flow without message bodies, raw email, IP,
   idempotency key, bearer token, or PostgreSQL DETAIL.

## 5. Failure semantics and cleanup

Do not deliberately stop RDS, SQS, or the running task during this short paid demonstration unless
separately approved. Phase 9 and the JVM integration suite already test dependency outages and
graceful shutdown locally. Remove `/tmp/crowdpass-phase10-fixture.sql`, unset token/password
variables, exit the session, collect sanitized evidence, and proceed directly to teardown.
