# CrowdPass temporary AWS infrastructure

This root Terraform stack reproduces the private, temporary AWS architecture verified in Phase 10.
It is intentionally a single understandable stack rather than a reusable multi-environment platform.
Terraform creates infrastructure only; it does not build or push the CrowdPass application image.

## Toolchain and authentication

- Terraform `~> 1.16.0` (validated with 1.16.4).
- AWS provider `~> 6.66.0`.
- Random provider `~> 3.9.0` for ephemeral secret generation.
- Local apply/destroy uses the AWS provider default credential chain after short-lived `aws login`.
- No static AWS credential belongs in Terraform, `.tfvars`, state, plans, GitHub, or this repository.

The provider lock file includes checksums for local `darwin_arm64` and GitHub CI `linux_amd64`.

## Local state lifecycle

The stack deliberately uses local state: this is one temporary environment operated by one person,
and a persistent S3 backend would add bootstrap, teardown, and survivor-audit complexity. Terraform
state, backup state, plan files, crash logs, and `.terraform` are ignored by Git.

Apply and destroy must occur from the same controlled, encrypted workstation. Before and after each
mutating operation, copy state to a restricted location outside the repository. Retain both the live
state and backup until `terraform destroy`, task-revision cleanup, GitHub-variable cleanup, and the
AWS survivor audit are complete. Before any recovery with `terraform state push`, verify lineage and
serial; never force a state push simply to bypass a mismatch.

## Secrets and repeat-apply behavior

Terraform generates three independent ephemeral values: the database password, JWT signing secret,
and rate-limit HMAC secret. Secret SSM parameters use `value_wo`; RDS uses `password_wo`; no raw
secret is stored in state, a saved plan, an output, or a variable file.

The database password uses one shared `database_password_generation`, initially `1`. SSM persists
the password and an ephemeral SSM read supplies that authoritative persisted value to RDS. RDS is
also replacement-coupled to the managed password parameter so an independent parameter replacement
cannot silently leave the database on a different password. A no-op plan/apply does not rotate it.
A deliberate rotation increments the shared generation and updates both sides. Full stack recreation
generates a new password. JWT and HMAC use their own stable generation inputs.

Do not target-replace or manually rotate the database-password parameter. If recovery requires a
password change, increment the shared generation and review the complete plan.

## Infrastructure and ownership

Terraform owns the VPC, three subnets, routes, security groups, private Single-AZ PostgreSQL 17 RDS,
Standard SQS queue/DLQ, SSM parameters, ECR repository, CloudWatch log group, ECS cluster/bootstrap
service, task roles, GitHub deployment role, and the GitHub OIDC provider when one is not supplied.

The application subnet has an Internet Gateway route and the task receives a public IPv4 address for
outbound access, but its security group has zero ingress. Both database subnets remain local-only.
There is no NAT Gateway, VPC endpoint, ALB, public RDS, ElastiCache, autoscaling, or public API path.

Terraform creates the ECS service with desired count zero and a nonexistent sentinel application
image. No bootstrap task executes. The first approved `AWS Demo Deploy` run pushes or reuses the
immutable `git-<full-sha>` image, clones the reviewed task definition, changes only the application
image, and scales the service to one.

After that deployment:

- Terraform owns CPU/memory, ARM64 runtime, network mode, roles, SSM references, Redis, logs, health
  configuration, networking, and other base runtime controls.
- CD owns the application digest, post-bootstrap task-definition revisions, active service revision,
  and desired count.
- Terraform ignores only service `task_definition` and `desired_count`; it does not ignore other
  infrastructure drift.

Base runtime changes after CD require deliberate stack recreation and redeployment for this temporary
architecture.

## Offline validation

No AWS credentials are required for:

```bash
terraform fmt -check -recursive
terraform init -backend=false -input=false
terraform validate
terraform test
../../scripts/ci/validate-terraform.sh
../../scripts/ci/test-terraform-invariants.sh
```

`terraform test` uses a provider-free, plan-only contract fixture. Terraform 1.16's provider-mocking
engine cannot instantiate ephemeral resource types, so mocking the production root would require
weakening or conditionally bypassing the real secret design. `terraform validate` checks the actual
provider schema, while static validation rejects excluded AWS services, unsafe
network/RDS/ECS/IAM changes, state-visible secrets, widened OIDC trust, and secret outputs. Mutation
tests prove those guardrails fail on representative unsafe changes.

## Phase 12C apply prerequisites

Do not run a live plan or apply until separately approved. Phase 12C must first verify the AWS
account and `us-west-2`, Free-plan/credit status, budget alerts, OIDC-provider ownership choice,
fixed optional `expires_at`, start time, and ready teardown procedure. Review the complete plan before
apply, then run a second plan after apply and require no changes.

## Non-secret GitHub output contract

After apply, explicitly map these outputs into the existing `aws-demo` GitHub Environment:

- `aws_region` → `AWS_REGION`
- `github_deployment_role_arn` → `AWS_DEPLOY_ROLE_ARN`
- `ecr_repository_name` → `ECR_REPOSITORY_NAME`
- `ecr_repository_uri` → `ECR_REPOSITORY_URI`
- `ecs_cluster_name` → `ECS_CLUSTER_NAME`
- `ecs_service_name` → `ECS_SERVICE_NAME`
- `ecs_task_definition_family` → `ECS_TASK_DEFINITION_FAMILY`
- `ecs_task_execution_role_arn` → `ECS_TASK_EXECUTION_ROLE_ARN`
- `ecs_application_task_role_arn` → `ECS_APPLICATION_TASK_ROLE_ARN`

For each allowlisted pair, use the form:

```bash
gh variable set AWS_REGION --env aws-demo --body "$(terraform output -raw aws_region)"
```

Repeat explicitly for the other eight mappings. Do not loop over every Terraform output, use a
GitHub Terraform provider, or synchronize secrets. After destroy, delete those exact nine variables
with `gh variable delete NAME --env aws-demo` and verify the Environment is empty.

## Deployment and teardown sequence

1. Phase 12C: approved preflight, plan, apply, infrastructure verification, no-op plan, and output
   synchronization.
2. Phase 11D: manually dispatch gated CD, then run private smoke/load verification and collect
   sanitized evidence.
3. Phase 12D: destroy Terraform-managed resources, remove CD-created task-definition revisions and
   GitHub variables, then perform exact-name/tag survivor and delayed-cost audits.

The ECR repository uses immutable tags and `force_delete = true`, so the application image cannot be
overwritten and does not block disposable-stack teardown. AWS service-linked roles and inactive ECS
control-plane metadata may remain because they are non-billable. CD-created task revisions are not in
Terraform state and require explicit family-scoped cleanup during Phase 12D.
