#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

SCENARIO=${1:?usage: run-scenario.sh <scenario> [run-name]}
RUN_NAME=${2:-"$SCENARIO-$(date -u +%Y%m%dT%H%M%SZ)"}
SCRIPT="$PERFORMANCE_DIR/k6/$SCENARIO.js"
if [ ! -f "$SCRIPT" ]; then
  printf 'unknown scenario: %s\n' "$SCENARIO" >&2
  exit 1
fi

wait_for_readiness
"$PERFORMANCE_DIR/scripts/sample-runtime.sh" "$RUN_NAME" &
SAMPLER_PID=$!
"$PERFORMANCE_DIR/scripts/sample-postgres.sh" "$RUN_NAME" &
POSTGRES_SAMPLER_PID=$!
cleanup() {
  kill "$SAMPLER_PID" 2>/dev/null || true
  kill "$POSTGRES_SAMPLER_PID" 2>/dev/null || true
  wait "$SAMPLER_PID" 2>/dev/null || true
  wait "$POSTGRES_SAMPLER_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

K6_ENV=()
for variable in RATE DURATION PRE_ALLOCATED_VUS MAX_VUS VUS ITERATIONS MAX_DURATION; do
  if [ -n "${!variable:-}" ]; then
    K6_ENV+=(--env "$variable=${!variable}")
  fi
done
"${COMPOSE[@]}" --profile app --profile load run --rm --no-deps "${K6_ENV[@]}" k6 run \
  --summary-export "/results/raw/$RUN_NAME-summary.json" "/scripts/$SCENARIO.js" \
  | tee "$RESULTS_DIR/raw/$RUN_NAME-console.txt"

cleanup
trap - EXIT INT TERM
printf 'Raw summary: %s\nRuntime samples: %s\nPostgreSQL samples: %s\n' \
  "$RESULTS_DIR/raw/$RUN_NAME-summary.json" "$RESULTS_DIR/runtime/$RUN_NAME.csv" \
  "$RESULTS_DIR/runtime/$RUN_NAME-postgres.csv"
