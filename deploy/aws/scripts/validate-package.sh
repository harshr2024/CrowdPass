#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
aws_dir="$repo_root/deploy/aws"
task="$aws_dir/ecs/task-definition.json"
plan="$aws_dir/resource-plan.json"
execution_policy="$aws_dir/iam/task-execution-policy.json"
application_policy="$aws_dir/iam/application-task-policy.json"
trust_policy="$aws_dir/iam/ecs-task-trust-policy.json"
queue="$aws_dir/sqs/notification-queue-attributes.json"
dlq="$aws_dir/sqs/notification-dlq-attributes.json"

find "$aws_dir" -name '*.json' -type f -print | while IFS= read -r file; do
  jq -e . "$file" >/dev/null
done

test "$(jq -r '.runtimePlatform.operatingSystemFamily' "$task")" = "LINUX"
test "$(jq -r '.runtimePlatform.cpuArchitecture' "$task")" = "ARM64"
test "$(jq -r '.cpu' "$task")" = "512"
test "$(jq -r '.memory' "$task")" = "2048"
test "$(jq -r '.containerDefinitions[] | select(.name == "crowdpass-api") | .user' "$task")" = "10001:10001"
test "$(jq -r '.containerDefinitions[] | select(.name == "crowdpass-api") | .readonlyRootFilesystem' "$task")" = "false"
test "$(jq -r '.containerDefinitions[] | select(.name == "crowdpass-api") | .linuxParameters.capabilities.drop[]' "$task")" = "ALL"
test "$(jq -r '.containerDefinitions[] | select(.name == "crowdpass-api") | .portMappings // [] | length' "$task")" = "0"
test "$(jq -r '.containerDefinitions[] | select(.name == "crowdpass-api") | [.environment[] | select(.name == "REDIS_HOST") | .value] | join("")' "$task")" = "127.0.0.1"
test "$(jq -r '.containerDefinitions[] | select(.name == "crowdpass-api") | [.environment[] | select(.name == "CROWDPASS_SQS_REGION") | .value] | join("")' "$task")" = "us-west-2"
test "$(jq -r '.containerDefinitions[] | select(.name == "redis") | .essential' "$task")" = "false"
test "$(jq -r '.containerDefinitions[] | select(.name == "redis") | .user' "$task")" = "999:1000"
test "$(jq -r '.containerDefinitions[] | select(.name == "redis") | .command | join(" ")' "$task")" = "redis-server --save  --appendonly no --dir /tmp"
test "$(jq -r '[.containerDefinitions[].cpu] | add' "$task")" = "512"
test "$(jq -r '[.containerDefinitions[].memory] | add' "$task")" = "2048"
test "$(jq -r '.network.natGateways' "$plan")" = "0"
test "$(jq -r '.network.applicationLoadBalancers' "$plan")" = "0"
test "$(jq -r '.network.interfaceVpcEndpoints' "$plan")" = "0"
test "$(jq -r '.network.taskSecurityGroup.inbound | length' "$plan")" = "0"
test "$(jq -r '.rds.publiclyAccessible' "$plan")" = "false"
test "$(jq -r '.rds.multiAZ' "$plan")" = "false"
test "$(jq -r '.rds.dbSubnetCount' "$plan")" = "2"
test "$(jq -r '.sqs.maxReceiveCount' "$plan")" = "5"
test "$(jq -r '.ecs.cpuArchitecture' "$plan")" = "ARM64"
test "$(jq -r '.ecs.platformVersion' "$plan")" = "1.4.0"
test "$(jq -r '.ecs.publicIngress' "$plan")" = "false"
test "$(jq -r '.tags.Project' "$plan")" = "CrowdPass"
test "$(jq -r '.tags.Environment' "$plan")" = "temporary-demo"
test "$(jq -r '[.tags[] | select(.key == "Project" and .value == "CrowdPass")] | length' "$task")" = "1"
test "$(jq -r '[.tags[] | select(.key == "Environment" and .value == "temporary-demo")] | length' "$task")" = "1"
test "$(jq -r '.SqsManagedSseEnabled' "$queue")" = "true"
test "$(jq -r '.RedrivePolicy | fromjson | .maxReceiveCount' "$queue")" = "5"
test "$(jq -r '.SqsManagedSseEnabled' "$dlq")" = "true"

test "$(jq -r '[.Statement[] | select(.Sid == "UseOnlyNotificationQueue") | .Action[]] | sort | join(",")' "$application_policy")" = "sqs:DeleteMessage,sqs:GetQueueUrl,sqs:ReceiveMessage,sqs:SendMessage"
test "$(jq -r '[.Statement[] | select(.Sid == "UseOnlyNotificationQueue") | .Resource] | unique | length' "$application_policy")" = "1"
test "$(jq -r '[.Statement[] | select(.Sid == "TemporaryEcsExecChannels") | .Action[]] | sort | join(",")' "$application_policy")" = "ssmmessages:CreateControlChannel,ssmmessages:CreateDataChannel,ssmmessages:OpenControlChannel,ssmmessages:OpenDataChannel"
test "$(jq -r '[.Statement[] | select(.Sid == "InjectOnlyCrowdPassParameters") | .Action] | join(",")' "$execution_policy")" = "ssm:GetParameters"
test "$(jq -r '[.Statement[] | select(.Sid == "InjectOnlyCrowdPassParameters") | .Resource[]] | length' "$execution_policy")" = "4"
test "$(jq -r '.Statement[0].Condition.StringEquals["aws:SourceAccount"]' "$trust_policy")" = "__ACCOUNT_ID__"
test "$(jq -r '.Statement[0].Condition.ArnLike["aws:SourceArn"]' "$trust_policy")" = "arn:aws:ecs:us-west-2:__ACCOUNT_ID__:*"

if rg -n --hidden --glob '!README.md' --glob '!runbooks/**' --glob '!**/validate-package.sh' \
  '(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|aws_secret_access_key|CROWDPASS_SQS_ACCESS_KEY_ID|CROWDPASS_SQS_SECRET_ACCESS_KEY)' \
  "$aws_dir"; then
  printf 'possible static AWS credential material found\n' >&2
  exit 1
fi

if rg -n '"(hostPort|containerPort)"|CROWDPASS_SQS_ENDPOINT|elasticmq|elasticache' "$task"; then
  printf 'forbidden public-ingress, local-SQS, or excluded-service setting found\n' >&2
  exit 1
fi

if rg -n 'latest' "$task"; then
  printf 'unversioned container tag found\n' >&2
  exit 1
fi

if rg -n --pcre2 '(^|[^0-9-])[0-9]{12}([^0-9-]|$)' "$aws_dir"; then
  printf 'possible hardcoded AWS account ID found\n' >&2
  exit 1
fi

printf 'Phase 10B deployment package validation passed\n'
