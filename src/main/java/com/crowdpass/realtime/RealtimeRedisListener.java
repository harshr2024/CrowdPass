package com.crowdpass.realtime;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses fixed-schema signals and hands them to the bounded delivery executor. */
@Component
class RealtimeRedisListener implements MessageListener {

	private final JsonMapper json;
	private final RealtimeConnectionRegistry connections;
	private final MeterRegistry meters;

	RealtimeRedisListener(JsonMapper json, RealtimeConnectionRegistry connections, MeterRegistry meters) {
		this.json = json;
		this.connections = connections;
		this.meters = meters;
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		try {
			JsonNode root = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
			if (root == null || !root.isObject() || root.path("version").asInt(-1) != RealtimeSignal.VERSION
					|| !RealtimeSignal.NOTIFICATION_CREATED.equals(root.path("kind").asString())) {
				reject("schema");
				return;
			}
			UUID userId = UUID.fromString(root.path("userId").asString());
			UUID notificationId = UUID.fromString(root.path("notificationId").asString());
			connections.dispatch(userId, notificationId);
			meters.counter("crowdpass.realtime.signals.received", "outcome", "accepted").increment();
		}
		catch (JacksonException | IllegalArgumentException ex) {
			reject("malformed");
		}
	}

	private void reject(String reason) {
		meters.counter("crowdpass.realtime.signals.received", "outcome", "rejected", "reason", reason).increment();
	}
}
