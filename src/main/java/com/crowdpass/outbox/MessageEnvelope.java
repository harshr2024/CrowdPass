package com.crowdpass.outbox;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The versioned message contract: {@code {"id", "type", "version", "occurredAt", "data"}}.
 * {@code id} is the outbox event id and is identical on every resend. Readers ignore unknown fields
 * so producers can add fields without a version change; breaking changes increment {@code version}.
 */
public record MessageEnvelope(UUID id, String type, int version, Instant occurredAt, JsonNode data) {

	/** Thrown for messages that cannot be read as an envelope at all. */
	public static class InvalidEnvelopeException extends RuntimeException {
		public InvalidEnvelopeException(String reason) {
			super(reason);
		}
	}

	static String toJson(OutboxEvent event, JsonMapper mapper) {
		ObjectNode envelope = mapper.createObjectNode();
		envelope.put("id", event.getId().toString());
		envelope.put("type", event.getEventType());
		envelope.put("version", event.getSchemaVersion());
		envelope.put("occurredAt", event.getOccurredAt().toString());
		envelope.set("data", mapper.readTree(event.getData()));
		return mapper.writeValueAsString(envelope);
	}

	/** Tolerant reader: validates the envelope fields and ignores anything it does not know. */
	public static MessageEnvelope parse(String body, JsonMapper mapper) {
		JsonNode root;
		try {
			root = mapper.readTree(body);
		}
		catch (JacksonException ex) {
			throw new InvalidEnvelopeException("body is not valid JSON");
		}
		if (root == null || !root.isObject()) {
			throw new InvalidEnvelopeException("body is not a JSON object");
		}
		JsonNode version = root.path("version");
		if (!version.isInt()) {
			throw new InvalidEnvelopeException("version is missing or not an integer");
		}
		JsonNode data = root.path("data");
		if (!data.isObject()) {
			throw new InvalidEnvelopeException("data is missing or not an object");
		}
		return new MessageEnvelope(uuid(root, "id"), text(root, "type"), version.intValue(),
				instant(root, "occurredAt"), data);
	}

	public static UUID uuid(JsonNode node, String field) {
		try {
			return UUID.fromString(text(node, field));
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidEnvelopeException(field + " is not a UUID");
		}
	}

	private static Instant instant(JsonNode node, String field) {
		try {
			return Instant.parse(text(node, field));
		}
		catch (DateTimeParseException ex) {
			throw new InvalidEnvelopeException(field + " is not an ISO-8601 instant");
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (!value.isString() || value.asString().isBlank()) {
			throw new InvalidEnvelopeException(field + " is missing or not a string");
		}
		return value.asString();
	}

}
