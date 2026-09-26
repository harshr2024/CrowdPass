#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

"${COMPOSE[@]}" --profile app --profile load down --volumes --remove-orphans
printf 'Performance stack and its dedicated volumes were removed.\n'
