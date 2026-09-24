#!/bin/sh
set -eu

if [ "$#" -ne 4 ]; then
  printf 'usage: %s ACCOUNT_ID IMAGE_TAG RDS_ENDPOINT OUTPUT_FILE\n' "$0" >&2
  exit 2
fi

account_id=$1
image_tag=$2
rds_endpoint=$3
output_file=$4
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
template="$script_dir/../ecs/task-definition.json"

case "$account_id" in
  *[!0-9]*|'') printf 'ACCOUNT_ID must contain exactly 12 digits\n' >&2; exit 2 ;;
esac
test "${#account_id}" -eq 12 || { printf 'ACCOUNT_ID must contain exactly 12 digits\n' >&2; exit 2; }

jq \
  --arg account "$account_id" \
  --arg image_tag "$image_tag" \
  --arg rds_endpoint "$rds_endpoint" \
  'walk(if type == "string" then
      gsub("__ACCOUNT_ID__"; $account)
      | gsub("__IMAGE_TAG__"; $image_tag)
      | gsub("__RDS_ENDPOINT__"; $rds_endpoint)
    else . end)' \
  "$template" > "$output_file"

if rg -n '__[A-Z0-9_]+__' "$output_file"; then
  printf 'rendered task definition still contains placeholders\n' >&2
  exit 1
fi

jq -e . "$output_file" >/dev/null
printf 'rendered %s locally; no AWS API was called\n' "$output_file"
