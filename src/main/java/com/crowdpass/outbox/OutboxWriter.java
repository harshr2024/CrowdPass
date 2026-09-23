package com.crowdpass.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Appends an event to the outbox. Requires the caller's transaction, so the event commits or rolls
 * back together with the domain change that produced it.
 */
@Component
public class OutboxWriter {

	private final OutboxEventRepository repository;
	private final JsonMapper jsonMapper;

	OutboxWriter(OutboxEventRepository repository, JsonMapper jsonMapper) {
		this.repository = repository;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * @param data event-specific body, serialized as a JSON object; identifiers only, no personal data
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID append(String eventType, int schemaVersion, String aggregateType, UUID aggregateId, Object data,
			Instant occurredAt) {
		OutboxEvent event = new OutboxEvent(eventType, schemaVersion, aggregateType, aggregateId,
				jsonMapper.writeValueAsString(data), occurredAt);
		repository.saveAndFlush(event);
		return event.getId();
	}

}
