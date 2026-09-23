package com.crowdpass.notification;

import java.time.Instant;
import java.util.UUID;

/** {@code eventName} is read from the event at query time, so it reflects the current name. */
public record NotificationResponse(UUID id, NotificationType type, UUID eventId, String eventName,
		UUID reservationId, Instant occurredAt, Instant readAt) {
}
