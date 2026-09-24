# CrowdPass temporary AWS deployment package

This directory prepares the approved Phase 10C demonstration architecture. It contains local
templates and human-checkpointed runbooks only. Nothing here provisions AWS resources, and Phase
10B must not run the Phase 10C commands documented in the runbooks.

## Selected architecture

- Region: `us-west-2`.
- One ECS service with desired count one on Fargate platform 1.4.0, Linux ARM64, 0.5 vCPU and 2 GiB
  task memory.
- One CrowdPass container plus one non-essential, ephemeral Redis sidecar in the same task.
- The task runs in a public subnet with an AWS-provided public IPv4 address for outbound access.
  Its security group has no inbound rules; port 8080 is never publicly mapped.
- Single-AZ RDS PostgreSQL `db.t4g.micro`, encrypted 20 GiB gp3, private only. Its DB subnet group
  contains two private subnets in different Availability Zones, while the instance itself remains
  Single-AZ.
- Real Standard SQS notification queue and DLQ, ECR, CloudWatch Logs, and four standard-tier SSM
  `SecureString` parameters.
- No ALB, NAT Gateway, ElastiCache, public API endpoint, domain, Route 53, CloudFront, EFS, static AWS
  credentials, customer-managed KMS key, or production HA resources.

The Redis container is deliberately non-essential and has no individual-container restart policy.
If it exits, ECS leaves the CrowdPass container running; rate limiting and realtime fan-out retain
their existing short-timeout, fail-open behavior until the task is replaced. PostgreSQL/SQS
correctness is unaffected, Redis health is excluded from application readiness, and a non-essential
Redis health failure does not determine overall ECS task health.

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
- `runbooks/phase10c-provision.md`: proposed order for a separately approved Phase 10C.
- `runbooks/smoke-test.md`: ECS Exec verification workflow, prepared but not executed.
- `runbooks/evidence.md`: sanitized evidence checklist.
- `runbooks/teardown.md`: teardown prepared before provisioning.
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

## Phase boundary

Phase 10C requires a new explicit approval. Its first AWS mutation must be the cost-guardrail step
in `runbooks/phase10c-provision.md`, not application infrastructure.
