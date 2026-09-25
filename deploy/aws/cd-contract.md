# AWS demo CD contract

Phase 11C defines the contract for a future manual deployment. It does not create or discover AWS
infrastructure, and it must not be dispatched until Phase 12 has created the temporary demo target.
The workflow is `.github/workflows/deploy-aws.yml` (`AWS Demo Deploy`) and uses only
`workflow_dispatch`.

## GitHub gate and exact commit

The workflow accepts only `refs/heads/main` and checks out the full `github.sha` in every job.
Correctness validation completes before the deploy job can request an OIDC token: the Java 21 Maven
suite, offline deployment-package/privacy checks, and a native `linux/arm64` image build all run in
jobs with only `contents: read`.

The deploy job is attached to the `aws-demo` GitHub Environment and alone receives
`id-token: write`. Its concurrency group is `aws-demo-deploy` with cancellation disabled, so two
deployments cannot mutate ECS concurrently and a newer dispatch cannot cancel an active update.

Phase 12 values are intentionally absent today. The deploy job checks all required Environment
variables before the OIDC action. A premature dispatch therefore fails with the missing variable
names and makes no AWS request.

## OIDC trust contract

GitHub's repository API reports that immutable OIDC subjects are active. The repository was created
after the July 2026 immutable-subject cutoff. Phase 12 must create the GitHub OIDC provider with URL
`https://token.actions.githubusercontent.com` and audience `sts.amazonaws.com`, then restrict the
dedicated deployment role with `StringEquals` on both claims:

```json
{
  "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
  "token.actions.githubusercontent.com:sub": "repo:harshr2024@218149804/CrowdPass@1386522027:environment:aws-demo"
}
```

The numeric values are the public, immutable GitHub owner and repository identifiers required by
the current subject format. The environment claim prevents a branch-only or pull-request subject
from assuming the role. The `aws-demo` Environment separately permits deployments from `main`
only. Forks, other repositories, other environments, and arbitrary refs do not match this trust.

References: [GitHub OIDC subject reference](https://docs.github.com/en/actions/reference/security/oidc)
and [AWS credentials action OIDC guidance](https://github.com/aws-actions/configure-aws-credentials).

## Phase 12 outputs and GitHub Environment inputs

Phase 12 must expose these stable Terraform outputs without requiring the workflow to read Terraform
state:

- `aws_region` -> Environment variable `AWS_REGION`.
- `github_deployment_role_arn` -> `AWS_DEPLOY_ROLE_ARN`.
- `ecr_repository_name` -> `ECR_REPOSITORY_NAME`.
- `ecr_repository_uri` -> `ECR_REPOSITORY_URI`.
- `ecs_cluster_name` -> `ECS_CLUSTER_NAME`.
- `ecs_service_name` -> `ECS_SERVICE_NAME`.
- `ecs_task_definition_family` -> `ECS_TASK_DEFINITION_FAMILY`.
- `ecs_task_execution_role_arn` -> `ECS_TASK_EXECUTION_ROLE_ARN`.
- `ecs_application_task_role_arn` -> `ECS_APPLICATION_TASK_ROLE_ARN`.
- `cloudwatch_log_group_name` only if Phase 11D adds log-based deployment verification.

These are non-secret deployment coordinates. After a successful temporary `terraform apply`, an
explicit Phase 12/11D synchronization step copies the required outputs into the existing `aws-demo`
Environment variables. Terraform must not manage the GitHub repository or Environment through a
GitHub provider merely to populate these values, and Terraform must not receive a GitHub personal
access token for this purpose. No database, JWT, rate-limit, session, bearer-token, or AWS credential
value belongs in GitHub. Runtime secrets remain entirely in AWS, referenced by the ECS task
definition and injected at task startup.

## Deployment-role permission boundary

The future GitHub deployment role is not an infrastructure role. Phase 12 must limit it to:

- `ecr:GetAuthorizationToken` on `*`, because AWS does not support repository scoping for that
  operation.
- `ecr:DescribeRepositories`, `ecr:DescribeImages`, `ecr:BatchCheckLayerAvailability`,
  `ecr:GetDownloadUrlForLayer`, `ecr:BatchGetImage`, `ecr:InitiateLayerUpload`,
  `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, and `ecr:PutImage` on the exact CrowdPass
  repository wherever the action supports repository-level resources.
- `ecs:DescribeClusters`, `ecs:DescribeServices`, `ecs:ListTasks`, `ecs:DescribeTasks`,
  `ecs:DescribeTaskDefinition`, `ecs:ListTagsForResource`, `ecs:RegisterTaskDefinition`,
  `ecs:TagResource`, and `ecs:UpdateService`, scoped to the exact cluster, service, and
  task-definition family where AWS supports resource scoping. `ecs:TagResource` is used only to
  preserve the reviewed task-definition tags when registering a revision.
- `iam:PassRole` for only the exact CrowdPass task execution role and application task role, with
  `iam:PassedToService` equal to `ecs-tasks.amazonaws.com`.

The role must not create networking, databases, queues, repositories, ECS services, OIDC providers,
IAM roles, parameters, or infrastructure stacks; it must not run Terraform or tear infrastructure
down. ECS Exec permissions are excluded. Phase 11D may add only the minimum read/execute permissions
if an approved private health-check mechanism genuinely needs them.

## Image and task-definition ownership

Phase 12 must configure the ECR repository with immutable tags. The immutable tag is
`git-<full-sha>`, the OCI revision label is the same full SHA, and ECR's returned `sha256` digest
becomes the task-definition image reference. The deploy job rebuilds from
the same validated commit because GitHub job isolation would otherwise require transferring a large
Docker image artifact. On the native ARM64 deployment runner, that rebuilt image is validated again
and the exact local image is pushed; it is not rebuilt after validation.

Phase 12 owns the cluster, roles, runtime configuration, bootstrap task definition, and the service
at desired count zero. No bootstrap task runs and the sentinel application image is deliberately
nonexistent, so infrastructure creation is not represented as an application deployment. The
service lifecycle ignores only the active task revision and desired count after CD takes ownership.
CD reads the task definition currently
bound to the service, verifies the approved ARM64/CPU/memory/container/role invariants, removes only
AWS response metadata, and changes only the `crowdpass-api` image. The repository's Phase 10 task
template remains historical deployment evidence rather than a second live renderer.

Base runtime changes after a CD deployment require deliberate stack recreation and redeployment for
this temporary architecture. Terraform must not roll a running service back to its bootstrap task
definition, and CD must not provision or modify the underlying infrastructure.

## Deployment and rollback behavior

After all validation jobs pass, the future deployment:

1. enters `aws-demo` and verifies every Phase 12 input;
2. assumes the dedicated role through OIDC;
3. confirms the exact ECR repository, ECS cluster, service, and task-definition family exist;
4. builds and validates the exact native ARM64 image, then checks immutable tag `git-<full-sha>`;
   if absent it pushes the image, while if present it pulls and validates that existing image's OCI
   revision against the same full SHA before reusing its digest;
5. resolves ECR's digest and renders a new task revision from the service's current revision;
6. registers that revision, updates the existing service to desired count one, and waits for
   stability;
7. verifies the service revision, exactly one intended running task, its image digest, and the
   completed ECS rollout; and
8. reports the SHA, tag, digest, cluster/service, and task-definition revision.

If the image push succeeds but registration or service update fails, the immutable image remains in
ECR for diagnosis. Rerunning the same Git SHA does not overwrite the immutable tag: it validates
and reuses the existing digest. If registration succeeds before the update
fails, the service remains on its previous revision. If rollout fails, the Phase 12 ECS circuit
breaker owns automatic rollback; the workflow then fails because the service or digest does not
match the requested revision. ECS retains the previous task-definition revision for recovery. No
custom rollback shell sequence competes with ECS native rollback.
