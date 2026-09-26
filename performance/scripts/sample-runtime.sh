#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

NAME=${1:?usage: sample-runtime.sh <run-name>}
OUTPUT="$RESULTS_DIR/runtime/$NAME.csv"
TOKEN=$(jq -r '.tokens[0]' "$RUNTIME_DIR/tokens.json")
printf 'timestamp,app_cpu_percent,app_memory_mib,heap_used_mib,heap_committed_mib,gc_count,gc_time_seconds,live_threads,hikari_active,hikari_pending,hikari_max\n' > "$OUTPUT"

metric() {
  local name=$1
  local statistic=${2:-VALUE}
  printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" | \
    curl --config - --fail --silent --show-error "http://127.0.0.1:8080/actuator/metrics/$name" | jq -r \
    --arg statistic "$statistic" '[((.measurements // [])[]) | select(.statistic == $statistic) | .value][0] // 0'
}

while :; do
  STATS=$("${COMPOSE[@]}" stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' app | tail -n 1)
  CPU=$(printf '%s' "$STATS" | awk -F, '{gsub(/%/, "", $2); print $2+0}')
  MEMORY=$(printf '%s' "$STATS" | awk -F, '{split($3,a," /"); value=a[1]; if (value ~ /GiB/) {gsub(/GiB/,"",value); value*=1024} else {gsub(/MiB/,"",value)}; print value+0}')
  HEAP_USED=$(metric 'jvm.memory.used?tag=area:heap')
  HEAP_COMMITTED=$(metric 'jvm.memory.committed?tag=area:heap')
  printf '%s,%s,%s,%.3f,%.3f,%s,%s,%s,%s,%s,%s\n' "$(date -u +%FT%TZ)" "$CPU" "$MEMORY" \
    "$(awk -v bytes="$HEAP_USED" 'BEGIN { print bytes / 1048576 }')" \
    "$(awk -v bytes="$HEAP_COMMITTED" 'BEGIN { print bytes / 1048576 }')" \
    "$(metric jvm.gc.pause COUNT)" "$(metric jvm.gc.pause TOTAL_TIME)" "$(metric jvm.threads.live)" \
    "$(metric hikaricp.connections.active)" "$(metric hikaricp.connections.pending)" \
    "$(metric hikaricp.connections.max)" >> "$OUTPUT"
  sleep 1
done
