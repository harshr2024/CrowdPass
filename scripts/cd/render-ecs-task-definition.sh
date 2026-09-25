#!/bin/sh
set -eu

if [ "$#" -ne 7 ]; then
  printf 'usage: %s INPUT OUTPUT IMAGE FAMILY EXECUTION_ROLE TASK_ROLE APP_CONTAINER\n' "$0" >&2
  exit 2
fi

input=$1
output=$2
image=$3
family=$4
execution_role=$5
task_role=$6
app_container=$7

jq -e \
  --arg family "$family" \
  --arg execution_role "$execution_role" \
  --arg task_role "$task_role" \
  --arg app "$app_container" \
  '.family == $family
   and .executionRoleArn == $execution_role
   and .taskRoleArn == $task_role
   and .networkMode == "awsvpc"
   and .cpu == "512"
   and .memory == "2048"
   and .runtimePlatform.operatingSystemFamily == "LINUX"
   and .runtimePlatform.cpuArchitecture == "ARM64"
   and (.requiresCompatibilities | index("FARGATE") != null)
   and ([.containerDefinitions[] |
     select(.name == $app
       and .essential == true
       and .cpu == 384
       and .memory == 1792
       and .user == "10001:10001"
       and .privileged == false
       and (.portMappings // [] | length) == 0
       and (.linuxParameters.capabilities.drop == ["ALL"]))] | length) == 1
   and ([.containerDefinitions[] |
     select(.name == "redis"
       and .essential == false
       and .cpu == 128
       and .memory == 256
       and .user == "999:1000"
       and .privileged == false)] | length) == 1
   and ([.containerDefinitions[].cpu] | add) == 512
   and ([.containerDefinitions[].memory] | add) == 2048' \
  "$input" >/dev/null

jq \
  --arg image "$image" \
  --arg app "$app_container" \
  'del(
     .taskDefinitionArn,
     .revision,
     .status,
     .requiresAttributes,
     .compatibilities,
     .registeredAt,
     .registeredBy,
     .deregisteredAt
   )
   | (.containerDefinitions[] | select(.name == $app).image) = $image' \
  "$input" > "$output"

current_normalized=$(mktemp)
next_normalized=$(mktemp)
trap 'rm -f "$current_normalized" "$next_normalized"' EXIT HUP INT TERM

jq -S \
  --arg app "$app_container" \
  'del(
     .taskDefinitionArn,
     .revision,
     .status,
     .requiresAttributes,
     .compatibilities,
     .registeredAt,
     .registeredBy,
     .deregisteredAt
   )
   | (.containerDefinitions[] | select(.name == $app).image) = "__IMAGE__"' \
  "$input" > "$current_normalized"
jq -S \
  --arg app "$app_container" \
  '(.containerDefinitions[] | select(.name == $app).image) = "__IMAGE__"' \
  "$output" > "$next_normalized"

cmp -s "$current_normalized" "$next_normalized"
printf 'rendered task definition by changing only container %s image\n' "$app_container"
