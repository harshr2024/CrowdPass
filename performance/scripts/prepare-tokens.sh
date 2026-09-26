#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command python3
TOKEN_COUNT=${1:?usage: prepare-tokens.sh <token-count>}
python3 "$PERFORMANCE_DIR/scripts/prepare-tokens.py" "$RUNTIME_DIR" "$TOKEN_COUNT"
