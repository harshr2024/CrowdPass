# CrowdPass temporary AWS deployment package

This directory records the reviewed package used for the approved Phase 10C temporary AWS
deployment. The templates do not provision resources by themselves; the human-checkpointed
runbooks were executed only after approval. The deployment was verified and then torn down.

## Exercised architecture

- Region: `us-west-2`.
- One ECS service with desired count one on Fargate platform 1.4.0, Linux ARM64, 0.5 vCPU and 2 GiB
  task memory.
- One CrowdPass container plus one non-essential, ephemeral Redis sidecar in the same task.
- The task runs in a public subnet with an AWS-provided public IPv4 address for outbound access.
  Its security group has no inbound rules; port 8080 is never publicly mapped.
- Single-AZ RDS PostgreSQL 17.11 on `db.t4g.micro`, encrypted 20 GiB gp3, private only. Its DB
  subnet group contained two private subnets in different Availability Zones, while the instance
  itself remained Single-AZ.
- Real Standard SQS notification queue and DLQ, ECR, CloudWatch Logs, and four standard-tier SSM
  `SecureString` parameters.
- No ALB, NAT Gateway, ElastiCache, public API endpoint, domain, Route 53, CloudFront, EFS, static AWS
  credentials, customer-managed KMS key, or production HA resources.

The Redis container is deliberately non-essential and has no individual-container restart policy.
If it exits, ECS leaves the CrowdPass container running; rate limiting and realtime fan-out retain
their existing short-timeout, fail-open behavior until the task is replaced. PostgreSQL/SQS
correctness is unaffected, Redis health is excluded from application readiness, and a non-essential
Redis health failure does not determine overall ECS task health.

## Verified Phase 10C deployment

CrowdPass ran in `us-west-2` for approximately 3 hours 50 minutes while the account remained on
the AWS Free plan. Raw usage was estimated at approximately $0.19-$0.20 before credits. Flyway
applied all five migrations through V5, and both `/livez` and `/readyz` reported `UP`.

The private ECS Exec smoke test verified registration and login, a `CONFIRMED` reservation,
same-reservation HTTP idempotency replay, FIFO `WAITING` position one, synchronous promotion on
cancellation, transactional-outbox publication, real Standard SQS delivery, and one durable
notification protected by PostgreSQL `source_event_id` idempotency. SSE delivered realtime events,
the Redis sidecar returned `PONG`, and no application `ERROR` log event was observed. Messaging is
at-least-once; this deployment does not claim exactly-once delivery.

Teardown removed all potentially billable CrowdPass resources, including compute, database,
snapshots/backups, queues, image storage, logs, parameters, and networking. Only non-billable
inactive ECS control-plane metadata and AWS service-linked-role metadata may remain.

## Files

- `resource-plan.json`: names, tags, network, ECS, RDS, SQS, SSM, and logging decisions.
- `ecs/task-definition.json`: placeholder-based ECS task definition for the two containers.
- `iam/`: task trust policy and separate execution/application role policies.
- `sqs/`: Standard queue and DLQ attribute templates; `maxReceiveCount` remains 5 as in Phase 6.
- `fixtures/seed-event.sql`: explicit disposable event fixture for the private smoke test only.
- `scripts/build-arm64-image.sh`: local `linux/arm64` Buildx build and metadata verification; never
  pushes.
- `scripts/render-task-definition.sh`: local placeholder substitution; never calls AWS.
- `scripts/validate-package.sh`: offline JSON and invariant validation.
- `runbooks/phase10c-provision.md`: approved provisioning order used for Phase 10C.
- `runbooks/smoke-test.md`: ECS Exec verification workflow used for the private smoke test.
- `runbooks/evidence.md`: sanitized evidence checklist.
- `runbooks/teardown.md`: dependency-ordered teardown checklist used after verification.
- `cost.md`: corrected raw estimates and assumptions.

## ECS Exec exception

The Docker image remains unchanged and Phase 9 proves it runs with a read-only root filesystem.
AWS documents that ECS Exec's managed SSM agent must write inside the target container and executes
commands as root. Therefore the temporary task definition deliberately sets
`readonlyRootFilesystem: false` for `crowdpass-api`, while the Java process still runs as fixed
UID/GID `10001:10001`, privileged mode stays off, and all Linux capabilities are dropped.

This is a temporary evidence-gathering exception, not a production hardening recommendation. ECS
Exec must be enabled only for the demonstration window, operator permissions must be tightly
limited, and the task must be destroyed during teardown. The Redis sidecar also has a writable
ephemeral root because Fargate does not provide Compose-style tmpfs mounts; persistence is disabled
and no volume is attached.

## Secrets and credentials

The four parameter names are:

- `/crowdpass/temporary-demo/db/username`
- `/crowdpass/temporary-demo/db/password`
- `/crowdpass/temporary-demo/jwt-secret`
- `/crowdpass/temporary-demo/rate-limit-secret`

They are standard `SecureString` parameters encrypted with the normal AWS-managed `aws/ssm` key.
Only their ARNs appear in the task template. The ECS task execution role receives
`ssm:GetParameters` on those exact ARNs so the ECS agent can inject them. Because the default
AWS-managed key is selected, the execution role does not need an additional customer-key
`kms:Decrypt` grant. Access isolation for these temporary secrets therefore comes primarily from
the execution role's narrow SSM parameter permissions; there is no dedicated KMS key policy
boundary.

The application task role has no Parameter Store or ECR access. It uses task-role credentials from
the AWS SDK default credential provider and can call only the required SQS actions on the main
notification queue. The four `ssmmessages` channel actions are separate and exist solely for the
temporary ECS Exec workflow. Both role trust relationships are restricted by source account and
regional ECS source ARN. No SQS endpoint override or static AWS access key is configured.

## Local validation

```bash
deploy/aws/scripts/validate-package.sh
deploy/aws/scripts/build-arm64-image.sh
```

The second command builds a local image explicitly with `--platform linux/arm64 --load`. It does not
authenticate to AWS and does not push the image.

## Phase 10 completion

Phase 10C is complete. The temporary environment must not be recreated without a new explicit
approval beginning with cost guardrails and the preflight in `runbooks/phase10c-provision.md`.
