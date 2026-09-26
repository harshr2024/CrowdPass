#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

NAME=${1:?usage: sample-runtime.sh <run-name>}
OUTPUT="$RESULTS_DIR/runtime/$NAME.csv"
TOKEN=$(jq -r '.tokens[0]' "$RUNTIME_DIR/tokens.json")
printf 'timestamp,app_cpu_percent,app_memory_mib,postgres_active,hikari_active,hikari_pending,hikari_max\n' > "$OUTPUT"

metric() {
  printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" | \
    curl --config - --fail --silent --show-error "http://127.0.0.1:8080/actuator/metrics/$1" | jq -r \
    '[((.measurements // [])[]) | select(.statistic == "VALUE") | .value][0] // 0'
}

while :; do
  STATS=$("${COMPOSE[@]}" stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' app | tail -n 1)
  CPU=$(printf '%s' "$STATS" | awk -F, '{gsub(/%/, "", $2); print $2+0}')
  MEMORY=$(printf '%s' "$STATS" | awk -F, '{split($3,a," /"); value=a[1]; if (value ~ /GiB/) {gsub(/GiB/,"",value); value*=1024} else {gsub(/MiB/,"",value)}; print value+0}')
  ACTIVE=$("${COMPOSE[@]}" exec --no-TTY postgres psql --username crowdpass --dbname crowdpass \
    --tuples-only --no-align --command "SELECT count(*) FROM pg_stat_activity WHERE datname='crowdpass' AND state='active';" | tr -d '[:space:]')
  printf '%s,%s,%s,%s,%s,%s,%s\n' "$(date -u +%FT%TZ)" "$CPU" "$MEMORY" "$ACTIVE" \
    "$(metric hikaricp.connections.active)" "$(metric hikaricp.connections.pending)" \
    "$(metric hikaricp.connections.max)" >> "$OUTPUT"
  sleep 1
done
