#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
workflow="$repo_root/.github/workflows/deploy-aws.yml"

test -f "$workflow"

ruby -ryaml -e '
  workflow = YAML.load_file(ARGV.fetch(0))
  triggers = workflow["on"] || workflow[true]
  abort "deployment workflow must use workflow_dispatch only" unless triggers == {"workflow_dispatch" => nil}
' "$workflow"

test "$(grep -c 'id-token: write' "$workflow")" -eq 1
test "$(grep -c 'contents: read' "$workflow")" -eq 2
grep -q 'refs/heads/main' "$workflow"
grep -q 'name: aws-demo' "$workflow"
grep -q 'group: aws-demo-deploy' "$workflow"
grep -q 'cancel-in-progress: false' "$workflow"
grep -q 'needs:' "$workflow"
grep -q 'audience: sts.amazonaws.com' "$workflow"
grep -q 'ImageNotFoundException' "$workflow"
grep -q 'docker pull "$image"' "$workflow"
grep -q 'publish_mode="reused"' "$workflow"
grep -q 'publish_mode="pushed"' "$workflow"
grep -q -- '--desired-count 1' "$workflow"
grep -q '.services\[0\].desiredCount == 0 or .services\[0\].desiredCount == 1' "$workflow"
grep -q '.services\[0\].runningCount == 1' "$workflow"
grep -q 'for attempt in $(seq 1 60)' "$workflow"
grep -q 'rolloutState == "FAILED"' "$workflow"
grep -q 'Timed out waiting for ECS primary deployment rollout completion' "$workflow"
grep -q '.taskArns | length == 1' "$workflow"
test "$(grep -c 'docker push "$image"' "$workflow")" -eq 1
test "$(grep -c './scripts/ci/validate-production-image.sh "$image" "$GITHUB_SHA"' "$workflow")" -eq 2

if grep -nE '^[[:space:]]*(push|pull_request|schedule):' "$workflow"; then
  printf 'deployment workflow contains a non-manual trigger\n' >&2
  exit 1
fi

if grep -nE '(aws-access-key-id|aws-secret-access-key|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|AWS_SESSION_TOKEN)' "$workflow"; then
  printf 'deployment workflow references static AWS credentials\n' >&2
  exit 1
fi

if grep -nE '(terraform|cloudformation|create-repository|create-cluster|create-service|create-role|create-open-id-connect-provider|create-db-instance|create-queue|delete-cluster|delete-service|delete-repository)' "$workflow"; then
  printf 'deployment workflow contains infrastructure-provisioning or teardown commands\n' >&2
  exit 1
fi

if grep -nE '(put-image-tag-mutability|imageTagMutability == "MUTABLE")' "$workflow"; then
  printf 'deployment workflow weakens immutable ECR tags\n' >&2
  exit 1
fi

if grep -n 'uses:' "$workflow" | grep -Ev '@[0-9a-f]{40}[[:space:]]+# v[0-9]'; then
  printf 'deployment workflow contains an action that is not pinned to a full commit SHA\n' >&2
  exit 1
fi

printf 'AWS deployment workflow guardrail validation passed\n'
