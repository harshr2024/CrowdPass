package com.crowdpass.realtime;

import java.util.UUID;

record RealtimeSignal(int version, String kind, UUID userId, UUID notificationId) {

	static final int VERSION = 1;
	static final String NOTIFICATION_CREATED = "NOTIFICATION_CREATED";

	static RealtimeSignal notificationCreated(UUID userId, UUID notificationId) {
		return new RealtimeSignal(VERSION, NOTIFICATION_CREATED, userId, notificationId);
	}
}
