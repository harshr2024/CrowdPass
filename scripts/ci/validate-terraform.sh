#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
terraform_dir=${1:-"$repo_root/infra/terraform"}

fail() {
  printf 'Terraform invariant failed: %s\n' "$1" >&2
  exit 1
}

require_pattern() {
  pattern=$1
  file=$2
  message=$3
  grep -Eq "$pattern" "$file" || fail "$message"
}

reject_pattern() {
  pattern=$1
  path=$2
  message=$3
  if grep -REn --include='*.tf' "$pattern" "$path" >/dev/null; then
    fail "$message"
  fi
}

test -d "$terraform_dir" || fail "Terraform root is missing"

for forbidden in aws_nat_gateway aws_lb aws_vpc_endpoint aws_elasticache_cluster aws_elasticache_replication_group; do
  reject_pattern "resource[[:space:]]+\"$forbidden\"" "$terraform_dir" "forbidden $forbidden resource introduced"
done

network="$terraform_dir/networking.tf"
security="$terraform_dir/security-groups.tf"
database="$terraform_dir/database.tf"
secrets="$terraform_dir/secrets.tf"
ecs="$terraform_dir/ecs.tf"
iam="$terraform_dir/iam.tf"
oidc="$terraform_dir/oidc.tf"
ecr="$terraform_dir/ecr.tf"
outputs="$terraform_dir/outputs.tf"
locals_file="$terraform_dir/locals.tf"
lock_file="$terraform_dir/.terraform.lock.hcl"

test -f "$lock_file" || fail "committed provider lock file is missing"
require_pattern 'version[[:space:]]*=[[:space:]]*"6[.]66[.]0"' "$lock_file" "AWS provider lock version changed"
require_pattern 'version[[:space:]]*=[[:space:]]*"3[.]9[.]1"' "$lock_file" "Random provider lock version changed"
test "$(grep -c '"h1:' "$lock_file" || true)" -eq 4 || fail "lock file must include both providers for darwin_arm64 and linux_amd64"

require_pattern 'cidr_block[[:space:]]*=[[:space:]]*local[.]vpc_cidr' "$network" "approved VPC CIDR is not used"
require_pattern 'destination_cidr_block[[:space:]]*=[[:space:]]*"0[.]0[.]0[.]0/0"' "$network" "public application route is missing"
require_pattern 'map_public_ip_on_launch[[:space:]]*=[[:space:]]*false' "$network" "subnets must not implicitly assign public addresses"

ingress_count=$(grep -Ec '^resource "aws_vpc_security_group_ingress_rule"' "$security" || true)
test "$ingress_count" -eq 1 || fail "exactly one database ingress rule is permitted"
reject_pattern '^[[:space:]]*ingress[[:space:]]*\{' "$security" "inline security-group ingress is forbidden"
require_pattern 'referenced_security_group_id[[:space:]]*=[[:space:]]*aws_security_group[.]task[.]id' "$security" "database ingress must reference only the task security group"
require_pattern 'cidr_ipv4[[:space:]]*=[[:space:]]*"[$]\{cidrhost\(local[.]vpc_cidr, 2\)\}/32"' "$security" "DNS egress must target only the VPC resolver"

require_pattern 'engine_version[[:space:]]*=[[:space:]]*"17"' "$database" "RDS must use the PostgreSQL 17 major line"
require_pattern 'instance_class[[:space:]]*=[[:space:]]*"db[.]t4g[.]micro"' "$database" "RDS instance class changed"
require_pattern 'multi_az[[:space:]]*=[[:space:]]*false' "$database" "RDS must remain Single-AZ"
require_pattern 'publicly_accessible[[:space:]]*=[[:space:]]*false' "$database" "RDS must remain private"
require_pattern 'deletion_protection[[:space:]]*=[[:space:]]*false' "$database" "RDS deletion protection must remain disabled for the disposable stack"
require_pattern 'skip_final_snapshot[[:space:]]*=[[:space:]]*true' "$database" "RDS must not require a final snapshot"
require_pattern 'delete_automated_backups[[:space:]]*=[[:space:]]*true' "$database" "RDS automated backups must be removed at destroy"
require_pattern 'password_wo[[:space:]]*=' "$database" "RDS must use the write-only password argument"
reject_pattern '^[[:space:]]*password[[:space:]]*=' "$database" "state-visible RDS password argument is forbidden"
require_pattern 'replace_triggered_by[[:space:]]*=[[:space:]]*\[aws_ssm_parameter[.]database_password\]' "$database" "DB-password parameter changes must not silently desynchronize RDS"

test "$(grep -Ec '^[[:space:]]*value_wo[[:space:]]*=' "$secrets" || true)" -eq 3 || fail "all three secret parameters must use value_wo"
test "$(grep -Ec '^[[:space:]]*value[[:space:]]*=' "$secrets" || true)" -eq 1 || fail "only the non-secret database username may use ordinary SSM value"
test "$(grep -Ec '^[[:space:]]*value_wo_version[[:space:]]*=' "$secrets" || true)" -eq 3 || fail "every write-only SSM secret needs an explicit generation"
require_pattern 'ephemeral "aws_ssm_parameter" "database_password"' "$secrets" "RDS must read the persisted SSM database password ephemerally"
reject_pattern 'timestamp\(' "$terraform_dir" "timestamp() would cause perpetual Terraform drift"

require_pattern 'cpu_architecture[[:space:]]*=[[:space:]]*"ARM64"' "$ecs" "ECS runtime must remain ARM64"
require_pattern 'cpu[[:space:]]*=[[:space:]]*"512"' "$ecs" "task CPU changed"
require_pattern 'memory[[:space:]]*=[[:space:]]*"2048"' "$ecs" "task memory changed"
require_pattern 'image[[:space:]]*=[[:space:]]*local[.]redis_image' "$ecs" "Redis must use the reviewed digest-pinned image"
require_pattern 'essential[[:space:]]*=[[:space:]]*false' "$ecs" "Redis must remain nonessential"
require_pattern 'user[[:space:]]*=[[:space:]]*"999:1000"' "$ecs" "Redis must remain non-root"
require_pattern 'desired_count[[:space:]]*=[[:space:]]*0' "$ecs" "Terraform must create a zero-count bootstrap service"
require_pattern 'ignore_changes[[:space:]]*=[[:space:]]*\[task_definition, desired_count\]' "$ecs" "Terraform/CD service ownership changed"
require_pattern 'assign_public_ip[[:space:]]*=[[:space:]]*true' "$ecs" "Fargate needs explicit public IPv4 in the no-NAT design"
require_pattern 'enable_execute_command[[:space:]]*=[[:space:]]*true' "$ecs" "ECS Exec must remain enabled"

require_pattern 'image_tag_mutability[[:space:]]*=[[:space:]]*"IMMUTABLE"' "$ecr" "ECR tags must remain immutable"
require_pattern 'force_delete[[:space:]]*=[[:space:]]*true' "$ecr" "the disposable ECR repository must destroy after CD pushes an image"

require_pattern 'github_oidc_subject[[:space:]]*=[[:space:]]*"repo:harshr2024@218149804/CrowdPass@1386522027:environment:aws-demo"' "$locals_file" "GitHub OIDC subject must remain exact and immutable"
require_pattern 'github_oidc_audience[[:space:]]*=[[:space:]]*"sts[.]amazonaws[.]com"' "$locals_file" "GitHub OIDC audience changed"
require_pattern 'existing_github_oidc_provider_arn == null \? 1 : 0' "$oidc" "OIDC provider must be conditionally owned or reused"
reject_pattern '(AdministratorAccess|PowerUserAccess|ec2:\*|rds:\*|sqs:\*|ssm:\*|iam:\*)' "$iam" "broad managed-style IAM permissions are forbidden"
reject_pattern '(ec2:Create|rds:Create|sqs:CreateQueue|ssm:PutParameter|iam:Create)' "$iam" "GitHub deployment policy must not provision infrastructure"
test "$(grep -Ec 'Resource[[:space:]]*=[[:space:]]*"\*"' "$iam" || true)" -eq 6 || fail "unavoidable IAM wildcard resource set changed; review every wildcard explicitly"

output_names=$(grep -E '^output "' "$outputs" | sed -E 's/^output "([^"]+)".*/\1/' | sort | tr '\n' ' ')
expected_outputs='aws_region ecr_repository_name ecr_repository_uri ecs_application_task_role_arn ecs_cluster_name ecs_service_name ecs_task_definition_family ecs_task_execution_role_arn github_deployment_role_arn '
test "$output_names" = "$expected_outputs" || fail "root outputs must match the nine approved non-secret GitHub variables exactly"
reject_pattern '^output "[^\"]*(password|secret|token|credential)' "$outputs" "secret-like Terraform output introduced"

printf 'Terraform static invariant validation passed\n'
