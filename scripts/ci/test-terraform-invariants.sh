#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
source_dir="$repo_root/infra/terraform"
validator="$repo_root/scripts/ci/validate-terraform.sh"
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM

case_number=0

new_case() {
  case_number=$((case_number + 1))
  case_dir="$scratch/case-$case_number"
  mkdir "$case_dir"
  cp "$source_dir"/*.tf "$case_dir/"
  cp "$source_dir/.terraform.lock.hcl" "$case_dir/"
}

expect_rejected() {
  label=$1
  if "$validator" "$case_dir" >/dev/null 2>&1; then
    printf 'mutation was not rejected: %s\n' "$label" >&2
    exit 1
  fi
  printf 'Rejected mutation: %s\n' "$label"
}

"$validator" "$source_dir" >/dev/null

new_case
printf '\nresource "aws_nat_gateway" "forbidden" {}\n' >> "$case_dir/networking.tf"
expect_rejected 'NAT Gateway introduced'

new_case
printf '\nresource "aws_lb" "forbidden" {}\n' >> "$case_dir/networking.tf"
expect_rejected 'ALB introduced'

new_case
printf '\nresource "aws_vpc_endpoint" "forbidden" {}\n' >> "$case_dir/networking.tf"
expect_rejected 'VPC endpoint introduced'

new_case
sed -i.bak 's/publicly_accessible    = false/publicly_accessible    = true/' "$case_dir/database.tf"
expect_rejected 'public RDS enabled'

new_case
sed -i.bak 's/multi_az               = false/multi_az               = true/' "$case_dir/database.tf"
expect_rejected 'Multi-AZ RDS enabled'

new_case
printf '\nresource "aws_vpc_security_group_ingress_rule" "task_public" {}\n' >> "$case_dir/security-groups.tf"
expect_rejected 'task security-group ingress introduced'

new_case
sed -i.bak 's/referenced_security_group_id = aws_security_group.task.id/cidr_ipv4 = "0.0.0.0\/0"/' "$case_dir/security-groups.tf"
expect_rejected 'database security group widened'

new_case
sed -i.bak 's/deletion_protection      = false/deletion_protection      = true/' "$case_dir/database.tf"
expect_rejected 'RDS deletion protection enabled'

new_case
sed -i.bak 's/skip_final_snapshot      = true/skip_final_snapshot      = false/' "$case_dir/database.tf"
expect_rejected 'RDS final snapshot required'

new_case
sed -i.bak 's/cpu_architecture        = "ARM64"/cpu_architecture        = "X86_64"/' "$case_dir/ecs.tf"
expect_rejected 'wrong ECS CPU architecture'

new_case
sed -i.bak 's/essential = false/essential = true/' "$case_dir/ecs.tf"
expect_rejected 'Redis made essential'

new_case
sed -i.bak 's/desired_count   = 0/desired_count   = 1/' "$case_dir/ecs.tf"
expect_rejected 'bootstrap desired count changed'

new_case
sed -i.bak 's/repo:harshr2024@218149804\/CrowdPass@1386522027:environment:aws-demo/repo:harshr2024@218149804\/*:environment:*/' "$case_dir/locals.tf"
expect_rejected 'OIDC subject widened'

new_case
sed -i.bak 's/ecr:GetAuthorizationToken/ec2:*/' "$case_dir/iam.tf"
expect_rejected 'deployment IAM broadened'

new_case
sed -i.bak 's/password_wo         =/password            =/' "$case_dir/database.tf"
expect_rejected 'state-visible RDS password used'

new_case
sed -i.bak 's/value_wo         =/value            =/' "$case_dir/secrets.tf"
expect_rejected 'state-visible SSM secret value used'

new_case
printf '\noutput "database_password" { value = "forbidden" }\n' >> "$case_dir/outputs.tf"
expect_rejected 'secret output introduced'

printf 'Terraform mutation tests passed (%s unsafe changes rejected)\n' "$case_number"
