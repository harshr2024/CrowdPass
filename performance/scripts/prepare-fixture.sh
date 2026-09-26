#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command curl
require_command jq
require_command openssl

SCENARIO=${1:?usage: prepare-fixture.sh <scenario> <user-count> <capacity> [event-count]}
USER_COUNT=${2:?user count is required}
CAPACITY=${3:?capacity is required}
EVENT_COUNT=${4:-1}
EVENT_ID=$(event_id_for_scenario "$SCENARIO")

case "$USER_COUNT:$CAPACITY:$EVENT_COUNT" in
  *[!0-9:]*|0:*|*:0:*|*:*:0) printf 'counts must be positive integers\n' >&2; exit 1 ;;
esac

wait_for_readiness

"${COMPOSE[@]}" exec --no-TTY postgres \
  psql --username crowdpass --dbname crowdpass --set ON_ERROR_STOP=on \
  --command 'TRUNCATE idempotency_records, notifications, outbox_events, waitlist_entries, reservations, events, users RESTART IDENTITY CASCADE;' \
  >/dev/null

PASSWORD=$(openssl rand -base64 36 | tr -d '\n/=+' | cut -c1-36)
REGISTER_FILE="$RUNTIME_DIR/bootstrap-register.json"
jq --null-input \
  --arg email 'perf-bootstrap@example.invalid' \
  --arg password "$PASSWORD" \
  --arg displayName 'Performance Bootstrap' \
  '{email:$email,password:$password,displayName:$displayName}' > "$REGISTER_FILE"
chmod 600 "$REGISTER_FILE"

curl --fail --silent --show-error \
  --header 'Content-Type: application/json' \
  --data-binary "@$REGISTER_FILE" \
  http://127.0.0.1:8080/api/auth/register >/dev/null

PASSWORD_HASH=$("${COMPOSE[@]}" exec --no-TTY postgres \
  psql --username crowdpass --dbname crowdpass --tuples-only --no-align \
  --command "SELECT password_hash FROM users WHERE email='perf-bootstrap@example.invalid';")
if [ -z "$PASSWORD_HASH" ]; then
  printf 'could not obtain the disposable bootstrap password hash\n' >&2
  exit 1
fi

"${COMPOSE[@]}" exec --no-TTY postgres \
  psql --username crowdpass --dbname crowdpass --set ON_ERROR_STOP=on \
  --set scenario="$SCENARIO" \
  --set user_count="$USER_COUNT" \
  --set capacity="$CAPACITY" \
  --set event_count="$EVENT_COUNT" \
  --set event_id="$EVENT_ID" \
  --set password_hash="$PASSWORD_HASH" \
  --file /dev/stdin < "$PERFORMANCE_DIR/fixtures/prepare.sql" >/dev/null

jq --null-input \
  --arg scenario "$SCENARIO" \
  --arg eventId "$EVENT_ID" \
  --arg password "$PASSWORD" \
  --argjson userCount "$USER_COUNT" \
  --argjson capacity "$CAPACITY" \
  '{scenario:$scenario,eventId:$eventId,password:$password,userCount:$userCount,capacity:$capacity,emailPattern:"perf-user-%06d@example.invalid"}' \
  > "$RUNTIME_DIR/fixture.json"
chmod 600 "$RUNTIME_DIR/fixture.json"
rm -f "$REGISTER_FILE"

printf 'Prepared scenario=%s users=%s capacity=%s events=%s event_id=%s\n' \
  "$SCENARIO" "$USER_COUNT" "$CAPACITY" "$EVENT_COUNT" "$EVENT_ID"
