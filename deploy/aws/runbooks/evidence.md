# Phase 10C evidence checklist

Capture evidence only after the smoke test succeeds and before teardown. Redact AWS account IDs,
full ARNs, public/private IPs, RDS endpoints, email addresses, bearer tokens, passwords, parameter
values, request IDs tied to user activity, and unnecessary UUIDs.

- Cost guardrails: Budget thresholds and confirmed notification destination, with personal details
  redacted.
- Architecture: the approved network diagram and a short statement that this is a temporary,
  single-task, non-public proof rather than an HA production design.
- ECR: repository, immutable image tag/digest, image size, Linux ARM64 architecture, and scan result.
- ECS: cluster/service desired and running count one, Fargate, ARM64 task definition, healthy app,
  Redis sidecar, ECS Exec enabled, no load balancer, and no inbound task-SG rules.
- Runtime privilege: application image/user `10001:10001`; separate note that ECS Exec itself is
  root and required a writable task root filesystem for this demo.
- RDS: PostgreSQL, `db.t4g.micro`, Single-AZ, 20 GiB gp3, encrypted, not publicly accessible, and
  two-subnet DB subnet group. Redact endpoint and identifiers.
- SQS: Standard main queue, Standard DLQ, SQS-managed encryption, visibility timeout, and redrive
  `maxReceiveCount=5`.
- Health: sanitized `/livez` and `/readyz` output obtained through localhost inside ECS Exec.
- API behavior: successful registration/login assertions, idempotent confirmed reservation,
  FIFO waitlist entry, synchronous promotion after cancellation, and durable notification.
- Realtime: sanitized SSE sync/invalidation evidence and `redis-cli ping`; explain that Redis is
  optional/ephemeral and the durable notification comes from PostgreSQL/SQS consumption.
- Logs: Flyway migrations, application start, outbox publish and consumer handling, with no secret
  or identifier leakage.
- Timing and cost: UTC deployment start/end, raw list-price estimate, credit-adjusted expectation,
  and later Cost Explorer actual when metering becomes available.
- Teardown: before/after resource inventory proving billable resources and retained snapshots are
  gone.

Do not claim exactly-once messaging, production sizing, high availability, a public AWS deployment,
or `$0` raw price.
