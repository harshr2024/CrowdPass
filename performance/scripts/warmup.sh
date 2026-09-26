#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

wait_for_readiness
"${COMPOSE[@]}" --profile app --profile load run --rm --no-deps \
  --env RATE="${RATE:-20}" --env DURATION="${DURATION:-30s}" \
  --env PRE_ALLOCATED_VUS="${PRE_ALLOCATED_VUS:-100}" --env MAX_VUS="${MAX_VUS:-1000}" \
  k6 run --quiet /scripts/warmup.js
