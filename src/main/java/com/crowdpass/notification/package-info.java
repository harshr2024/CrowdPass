/**
 * In-app notifications produced by consuming domain events. The only event in this phase is
 * {@code WAITLIST_PROMOTED}. Delivery is at-least-once; {@code source_event_id} makes the insert
 * idempotent. This is not HTTP idempotency.
 */
package com.crowdpass.notification;
