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

if grep -n 'uses:' "$workflow" | grep -Ev '@[0-9a-f]{40}[[:space:]]+# v[0-9]'; then
  printf 'deployment workflow contains an action that is not pinned to a full commit SHA\n' >&2
  exit 1
fi

printf 'AWS deployment workflow guardrail validation passed\n'
