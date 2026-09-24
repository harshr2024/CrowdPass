# Phase 10 temporary deployment cost model

Prices were checked during Phase 10A on 2026-09-23. They must be rechecked before Phase 10C.
Figures are raw list-price estimates before credits, tax, data-transfer overages, CPU-credit
overages, or unexpected log volume.

The Phase 10A totals already included the task's AWS-provided public IPv4 address at `$0.005` per
address-hour, so no public-IPv4 correction is required. This audit separately adds the previously
omitted seven-day CloudWatch log-storage cost to the 30-day estimate:

| Window | Fargate ARM64 | Public IPv4 | RDS compute | RDS gp3 | ECR | SQS | Logs | Total |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 4 hours | $0.093 | $0.020 | $0.064 | $0.013 | <$0.001 | <$0.001 | $0.010 | **$0.20** |
| 24 hours | $0.559 | $0.120 | $0.384 | $0.077 | $0.001 | $0.002 | $0.060 | **$1.20** |
| 30 days / 720 hours | $16.78 | $3.60 | $11.52 | $2.30 | $0.024 | $0.052 | $1.82 | **$36.10** |

Assumptions:

- Fargate Linux ARM64: 0.5 vCPU and 2 GiB at $0.02331/task-hour.
- One in-use public IPv4 at $0.005/hour.
- RDS PostgreSQL `db.t4g.micro` Single-AZ at $0.016/hour and 20 GiB gp3 at
  $0.115/GiB-month.
- One 0.244 GB private ECR image at $0.10/GB-month, using basic scan-on-push. Paid enhanced or
  continuous Inspector scanning is excluded and must remain disabled for this repository.
- SQS polling every 20 seconds plus small demonstration traffic, costed at list price even though it
  remains under the one-million-request monthly allowance.
- CloudWatch Logs budgeted at 5 MB/hour and $0.50/GB ingestion. Seven-day retention adds about
  `$0.02` of raw archival storage over the modeled 30-day window, bringing that line to `$1.82`.
  This usage remains below the normal monthly free allowance, but the raw table does not subtract
  free-tier benefits.
- Four standard-tier Parameter Store values, IAM roles/policies, VPC/subnets/route tables/security
  groups, the Internet Gateway, and the basic Budget/email alerts are modeled at `$0`. No NAT,
  paid VPC endpoint, customer-managed KMS key, or Secrets Manager secret is created.
- Same-region AWS service calls, the one small ECR pull, and inbound Redis-image/package downloads
  are assumed to have no material data-transfer charge at demonstration volume. Recheck this and
  the account's Budget allowance before Phase 10C.
- Redis consumes capacity already included in the 2 GiB Fargate task; it has no separate service
  charge.
- Task and RDS instance use the same AZ; the second private subnet exists only to satisfy the RDS DB
  subnet-group requirement.

Expected out-of-pocket cost is approximately $0 only if the account has at least $0.20 of eligible,
unexpired credits. Without credits, the four-hour raw estimate is approximately $0.20. The revised
30-day raw estimate is approximately $36.10 after including seven-day log storage. Free/Paid
plan, credit balance, expiration, and AWS Organizations status remain Phase 10C preflight checks.
