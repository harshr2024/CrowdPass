# Proposed Phase 10C provisioning order

Do not execute this runbook without explicit Phase 10C approval. Every numbered section is a human
checkpoint; there is intentionally no one-command provisioning script.

## 0. Cost guardrails first

1. Sign in through the user's normal federated/temporary operator identity. Do not create an IAM
   user or static access key.
2. Verify account Free/Paid plan, creation date, usable credit balance, credit expiry, and whether
   joining AWS Organizations affected credits.
3. Confirm the selected region is `us-west-2`; record the account ID only in local ephemeral
   variables, never in Git.
4. Confirm that none of the exact resource names in `../resource-plan.json` already belongs to an
   unrelated workload. Stop on any collision rather than adopting, modifying, or later deleting it.
5. Create or verify a `$5` monthly AWS Budget. Configure email notifications at `$1` actual spend
   and `$5` forecast/actual spend. Verify the recipient confirms the notification.
6. Enable/verify Free Tier and billing notifications. A Budget alert is delayed monitoring, not a
   hard spending cap and not a replacement for teardown.
7. Set one UTC `ExpiresAt` value for all supported resource tags, no later than the same day.
8. Record deployment start time and reviewer/operator. Open `teardown.md` and prepare its inventory
   before creating application resources.

Stop if credits or account eligibility are unclear and the operator does not accept the raw cost.

## 1. Network foundation

1. Choose two available AZs in `us-west-2` and record them as primary and secondary.
2. Create the tagged `10.42.0.0/16` VPC and enable normal VPC DNS support/hostnames.
3. Create one `10.42.0.0/24` public application subnet in the primary AZ.
4. Create `10.42.10.0/24` and `10.42.11.0/24` private DB subnets in different AZs.
5. Attach one Internet Gateway. Route `0.0.0.0/0` from only the public subnet to it.
6. Do not create a NAT Gateway, default route from either DB subnet, paid interface endpoint, ALB,
   EIP, or inbound port-forwarding resource.

Checkpoint: route tables show the DB subnets as local-only.

## 2. Security groups

1. Create the task security group with **zero inbound rules**.
2. Allow task egress on TCP 443 for AWS public endpoints/image retrieval, TCP 5432 to the DB
   security group, and required DNS to the VPC resolver. Avoid a broad all-protocol egress rule.
3. Create the DB security group with exactly one inbound rule: TCP 5432 from the task security
   group. The DB group needs no public source range.

Checkpoint: there is no inbound rule for 8080, no `0.0.0.0/0` inbound rule, and no public RDS path.

## 3. Parameters and role policies

1. Generate a unique DB password plus independent, Base64-encoded 256-bit JWT and rate-limit HMAC
   secrets outside shell history and screenshots.
2. Create the four standard-tier `SecureString` parameters named in `../README.md`, using the
   AWS-managed `aws/ssm` key. Never print their values after creation.
3. Create execution and application task roles using `../iam/ecs-task-trust-policy.json`.
4. Substitute only the real account ID into the trust policy and the two permissions-policy
   templates. Use the trust policy for both roles; its source-account and regional ECS source-ARN
   conditions limit assumption to this account. Attach the execution policy only to the execution
   role and the application policy only to the task role.
5. Do not attach `AmazonSSMManagedInstanceCore`, `AmazonSQSFullAccess`, or other broad managed
   policies.

Checkpoint: the execution role can pull the one ECR repository, write the one log group, and read
the four parameter ARNs; the task role has only the main-queue calls and ECS Exec channels.

## 4. Logs, image repository, and queues

1. Create `/crowdpass/temporary-demo` with seven-day retention.
2. Create private ECR repository `crowdpass-api` with basic scan-on-push and the common tags. Check
   the registry scanning configuration first; do not silently opt this disposable repository into
   paid enhanced/continuous scanning.
3. Run `../scripts/build-arm64-image.sh` locally. Tag the verified ARM64 image with an immutable
   Phase 10C tag, authenticate, and push only after recording the local image digest.
4. Create the DLQ first using `../sqs/notification-dlq-attributes.json`.
5. Resolve its ARN locally, replace `__DLQ_ARN__` in the main queue attributes, then create the
   Standard main queue. Confirm `maxReceiveCount=5` and SQS-managed encryption.

Checkpoint: no ElasticMQ endpoint or static credentials appear in the task configuration.

## 5. RDS PostgreSQL

1. Create a DB subnet group containing both private DB subnets.
2. Create `crowdpass-temporary-demo` as PostgreSQL, `db.t4g.micro`, Single-AZ in the primary AZ,
   20 GiB gp3, encrypted storage, database `crowdpass`, private accessibility, backup retention 0,
   deletion protection off, no read replica and no enhanced paid features.
3. Supply the master username/password from the prepared secure values without writing them into a
   command file, source file, screenshot, or shell history.
4. Record only the non-secret RDS endpoint for task-template rendering.

Checkpoint: RDS is not publicly accessible, its SG source is only the task SG, and no snapshot was
created.

## 6. ECS service

1. Render the task definition locally with `../scripts/render-task-definition.sh`, writing the
   result outside the repository. Inspect it before registration.
2. Confirm `LINUX`/`ARM64`, task CPU 512, memory 2048, desired count one, app user `10001:10001`,
   capabilities dropped, no port mapping, no secret values, and no SQS endpoint override.
3. Register the task definition, create the tagged ECS cluster, and create a service in the public
   subnet with Fargate platform version `1.4.0`, the task SG, assigned public IP, desired count one,
   ECS Exec enabled, and no load balancer or autoscaling. Platform 1.4.0 is the minimum compatible
   Linux Fargate platform for both ARM64 and ECS Exec; do not silently fall back to an older version.
4. Confirm the app gets task-role credentials. Do not place AWS keys in environment variables.
5. Wait for the task's container health check and `/readyz` to pass. Inspect CloudWatch logs for all
   Flyway migrations and avoid copying sensitive values into evidence.

Checkpoint: the task has an outbound public IP but the SG has no inbound rules. The API is not
reachable from the public internet.

## 7. Private verification, evidence, and same-day teardown

1. Follow `smoke-test.md`; do not add ingress to simplify it.
2. Follow `evidence.md`, redacting account IDs, endpoints, tokens, emails, UUIDs, and secrets.
3. Record end time, then immediately execute `teardown.md` in order.
4. Keep cost notifications active until delayed billing data and the final resource inventory have
   been checked.
