#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

RATE_LIMIT_COMPOSE=("${COMPOSE[@]}" --file "$PERFORMANCE_DIR/rate-limit.compose.yaml")
"${RATE_LIMIT_COMPOSE[@]}" up --detach --force-recreate app
wait_for_readiness
"$PERFORMANCE_DIR/scripts/prepare-tokens.sh" 1
"${RATE_LIMIT_COMPOSE[@]}" --profile app --profile load run --rm --no-deps k6 run /scripts/rate-limit-smoke.js
printf 'Rate-limit smoke passed. Restart the normal performance stack before measuring a scenario.\n'
