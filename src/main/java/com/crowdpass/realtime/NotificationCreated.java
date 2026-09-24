package com.crowdpass.realtime;

import java.util.UUID;

/** Raised inside the notification transaction and observed only after that transaction commits. */
public record NotificationCreated(UUID userId, UUID notificationId) {
}
