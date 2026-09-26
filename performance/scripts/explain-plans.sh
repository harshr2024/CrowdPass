#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

wait_for_readiness
FIXTURE="$RUNTIME_DIR/fixture.json"
EVENT_ID=$(jq -r '.eventId' "$FIXTURE")
OUTPUT="$RESULTS_DIR/raw/query-plans.txt"

"${COMPOSE[@]}" exec --no-TTY postgres psql --username crowdpass --dbname crowdpass \
  --set ON_ERROR_STOP=on --file /dev/stdin > "$OUTPUT" <<SQL
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, organizer_id, name, description, capacity, reserved_count, status, time_zone,
       registration_open_at, registration_close_at, starts_at, ends_at, cancelled_at,
       created_at, updated_at
FROM events
WHERE status = 'PUBLISHED' AND ends_at > CURRENT_TIMESTAMP
ORDER BY starts_at, id
LIMIT 20;

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)
FROM events
WHERE status = 'PUBLISHED' AND ends_at > CURRENT_TIMESTAMP;

BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
UPDATE events
SET reserved_count = reserved_count + 1
WHERE id = '$EVENT_ID'::uuid
  AND status = 'PUBLISHED'
  AND registration_open_at <= CURRENT_TIMESTAMP
  AND registration_close_at > CURRENT_TIMESTAMP
  AND reserved_count < capacity;
ROLLBACK;
SQL

printf 'Local query plans written to ignored file %s\n' "$OUTPUT"
