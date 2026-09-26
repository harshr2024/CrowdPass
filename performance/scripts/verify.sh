#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/common.sh"

SCENARIO=${1:?usage: verify.sh <scenario> [expected-mutations]}
EXPECTED=${2:-}
EVENT_ID=$(event_id_for_scenario "$SCENARIO")

RESULT=$("${COMPOSE[@]}" exec --no-TTY postgres psql --username crowdpass --dbname crowdpass \
  --tuples-only --no-align --command "
    SELECT json_build_object(
      'capacity', e.capacity,
      'reservedCount', e.reserved_count,
      'confirmed', count(DISTINCT r.id) FILTER (WHERE r.status='CONFIRMED'),
      'duplicateConfirmed', (SELECT count(*) FROM (
        SELECT user_id FROM reservations WHERE event_id=e.id AND status='CONFIRMED' GROUP BY user_id HAVING count(*) > 1
      ) duplicates),
      'waiting', count(DISTINCT w.id) FILTER (WHERE w.status='WAITING'),
      'duplicateWaiting', (SELECT count(*) FROM (
        SELECT user_id FROM waitlist_entries WHERE event_id=e.id AND status='WAITING' GROUP BY user_id HAVING count(*) > 1
      ) duplicates),
      'waitingSequenceDistinct', count(DISTINCT w.queue_seq) FILTER (WHERE w.status='WAITING'),
      'waitingSequenceOrdered', COALESCE((SELECT bool_and(previous IS NULL OR queue_seq > previous) FROM (
        SELECT queue_seq, lag(queue_seq) OVER (ORDER BY queue_seq) previous FROM waitlist_entries WHERE event_id=e.id AND status='WAITING'
      ) ordered), true),
      'confirmedAndWaiting', (SELECT count(*) FROM reservations r2 JOIN waitlist_entries w2
        ON w2.event_id=r2.event_id AND w2.user_id=r2.user_id
        WHERE r2.event_id=e.id AND r2.status='CONFIRMED' AND w2.status='WAITING'),
      'idempotencyRecords', (SELECT count(*) FROM idempotency_records),
      'completedIdempotencyRecords', (SELECT count(*) FROM idempotency_records WHERE state='COMPLETED'),
      'idempotencyResponseMatches', COALESCE((SELECT bool_and((response_body->>'id')::uuid IN
        (SELECT id FROM reservations)) FROM idempotency_records WHERE state='COMPLETED'), true)
    )
    FROM events e
    LEFT JOIN reservations r ON r.event_id=e.id
    LEFT JOIN waitlist_entries w ON w.event_id=e.id
    WHERE e.id='$EVENT_ID'::uuid
    GROUP BY e.id;")

printf '%s\n' "$RESULT" | jq .
jq -e '
  .reservedCount == .confirmed and
  .confirmed <= .capacity and
  .duplicateConfirmed == 0 and
  .duplicateWaiting == 0 and
  .waiting == .waitingSequenceDistinct and
  .waitingSequenceOrdered and
  .confirmedAndWaiting == 0
' >/dev/null <<<"$RESULT"

case "$SCENARIO" in
  reservations)
    [ -z "$EXPECTED" ] || jq -e --argjson expected "$EXPECTED" '.confirmed == $expected' >/dev/null <<<"$RESULT" ;;
  hot-event)
    jq -e '.confirmed == .capacity and .reservedCount == .capacity' >/dev/null <<<"$RESULT" ;;
  waitlist)
    [ -z "$EXPECTED" ] || jq -e --argjson expected "$EXPECTED" '.waiting == $expected' >/dev/null <<<"$RESULT" ;;
  idempotency-storm)
    jq -e '.confirmed == 1 and .reservedCount == 1 and .idempotencyRecords == 1 and .completedIdempotencyRecords == 1 and .idempotencyResponseMatches' >/dev/null <<<"$RESULT" ;;
  idempotency-distinct)
    [ -z "$EXPECTED" ] || jq -e --argjson expected "$EXPECTED" '.confirmed == $expected and .completedIdempotencyRecords == $expected and .idempotencyResponseMatches' >/dev/null <<<"$RESULT" ;;
  mixed)
    [ -z "$EXPECTED" ] || jq -e --argjson expected "$EXPECTED" '.confirmed == $expected' >/dev/null <<<"$RESULT" ;;
esac
printf 'Correctness verification passed for %s.\n' "$SCENARIO"
