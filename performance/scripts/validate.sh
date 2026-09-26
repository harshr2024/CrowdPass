#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command docker
for script in "$PERFORMANCE_DIR"/scripts/*.sh; do
  bash -n "$script"
done
python3 -m py_compile "$PERFORMANCE_DIR/scripts/prepare-tokens.py"
rm -rf "$PERFORMANCE_DIR/scripts/__pycache__"
"${COMPOSE[@]}" --profile app --profile load config --quiet

CREATED_VALIDATION_FIXTURE=false
if [ ! -f "$RUNTIME_DIR/fixture.json" ] && [ ! -f "$RUNTIME_DIR/tokens.json" ]; then
  printf '%s\n' '{"scenario":"validation","eventId":"01960000-0000-7000-8000-000000000010","warmupEventId":"01960000-0000-7000-8000-000000000099","userCount":1,"capacity":1}' > "$RUNTIME_DIR/fixture.json"
  printf '%s\n' '{"tokens":["validation-only-not-a-jwt"]}' > "$RUNTIME_DIR/tokens.json"
  CREATED_VALIDATION_FIXTURE=true
fi
cleanup_validation_fixture() {
  if [ "$CREATED_VALIDATION_FIXTURE" = true ]; then
    rm -f "$RUNTIME_DIR/fixture.json" "$RUNTIME_DIR/tokens.json"
  fi
}
trap cleanup_validation_fixture EXIT

for scenario in "$PERFORMANCE_DIR"/k6/*.js; do
  "${COMPOSE[@]}" --profile app --profile load run --rm --no-deps k6 inspect "/scripts/$(basename "$scenario")" >/dev/null
done
cleanup_validation_fixture
trap - EXIT
printf 'Performance harness validation passed.\n'
