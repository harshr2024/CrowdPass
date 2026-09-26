#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command docker
require_command curl

"${COMPOSE[@]}" --profile app up --detach --build --wait postgres redis elasticmq app
wait_for_readiness

printf 'Performance stack is ready (project=%s).\n' "$COMPOSE_PROJECT_NAME"
