#!/usr/bin/env bash

set -euo pipefail

PERFORMANCE_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPOSITORY_DIR=$(cd "$PERFORMANCE_DIR/.." && pwd)
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-crowdpass-performance}
COMPOSE=(
  docker compose
  --project-name "$COMPOSE_PROJECT_NAME"
  --file "$REPOSITORY_DIR/compose.yaml"
  --file "$PERFORMANCE_DIR/compose.yaml"
)
RUNTIME_DIR="$PERFORMANCE_DIR/.runtime"
RESULTS_DIR="$PERFORMANCE_DIR/results"

mkdir -p "$RUNTIME_DIR" "$RESULTS_DIR/raw" "$RESULTS_DIR/runtime"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'required command is unavailable: %s\n' "$1" >&2
    exit 1
  fi
}

wait_for_readiness() {
  local attempt
  for attempt in $(seq 1 90); do
    if curl --fail --silent --show-error http://127.0.0.1:8080/readyz >/dev/null 2>&1; then
      printf 'CrowdPass is ready after %s readiness checks.\n' "$attempt"
      return 0
    fi
    sleep 2
  done
  printf 'CrowdPass did not become ready within 180 seconds.\n' >&2
  "${COMPOSE[@]}" logs --tail=200 app >&2 || true
  return 1
}

event_id_for_scenario() {
  case "$1" in
    public-reads) printf '01960000-0000-7000-8000-000000000010' ;;
    reservations) printf '01960000-0000-7000-8000-000000000020' ;;
    hot-event) printf '01960000-0000-7000-8000-000000000030' ;;
    waitlist) printf '01960000-0000-7000-8000-000000000040' ;;
    idempotency-storm|idempotency-distinct) printf '01960000-0000-7000-8000-000000000050' ;;
    mixed) printf '01960000-0000-7000-8000-000000000060' ;;
    *) printf 'unknown scenario: %s\n' "$1" >&2; return 1 ;;
  esac
}
