#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

NAME=${1:?usage: sample-postgres.sh <run-name>}
OUTPUT="$RESULTS_DIR/runtime/$NAME-postgres.csv"
POSTGRES_CONTAINER=$("${COMPOSE[@]}" ps --quiet postgres)
if [ -z "$POSTGRES_CONTAINER" ]; then
  printf 'PostgreSQL benchmark container is not running.\n' >&2
  exit 1
fi

printf 'timestamp,sessions,active,active_waiting,lock_waiting,io_waiting,lwlock_waiting,ungranted_locks,max_active_transaction_ms\n' > "$OUTPUT"
QUERY="COPY (
  SELECT clock_timestamp(),
         count(*) FILTER (WHERE pid <> pg_backend_pid()),
         count(*) FILTER (WHERE pid <> pg_backend_pid() AND state = 'active'),
         count(*) FILTER (WHERE pid <> pg_backend_pid() AND state = 'active' AND wait_event_type IS NOT NULL),
         count(*) FILTER (WHERE pid <> pg_backend_pid() AND state = 'active' AND wait_event_type = 'Lock'),
         count(*) FILTER (WHERE pid <> pg_backend_pid() AND state = 'active' AND wait_event_type = 'IO'),
         count(*) FILTER (WHERE pid <> pg_backend_pid() AND state = 'active' AND wait_event_type = 'LWLock'),
         (SELECT count(*) FROM pg_locks WHERE NOT granted AND pid <> pg_backend_pid()),
         COALESCE(max(EXTRACT(EPOCH FROM (clock_timestamp() - xact_start)) * 1000)
           FILTER (WHERE pid <> pg_backend_pid() AND state = 'active' AND xact_start IS NOT NULL), 0)
  FROM pg_stat_activity
  WHERE datname = current_database()
) TO STDOUT WITH CSV"

while :; do
  docker exec "$POSTGRES_CONTAINER" psql --username crowdpass --dbname crowdpass \
    --quiet --tuples-only --command "$QUERY" >> "$OUTPUT"
  sleep 0.2
done
