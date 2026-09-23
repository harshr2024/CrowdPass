package com.crowdpass.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

	static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
	private static final int MAX_ERROR_LENGTH = 300;

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "event_type", nullable = false, length = 100)
	private String eventType;

	@Column(name = "schema_version", nullable = false)
	private int schemaVersion;

	@Column(name = "aggregate_type", nullable = false, length = 50)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false)
	private UUID aggregateId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String data;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "last_error", length = MAX_ERROR_LENGTH)
	private String lastError;

	protected OutboxEvent() {
	}

	OutboxEvent(String eventType, int schemaVersion, String aggregateType, UUID aggregateId, String data,
			Instant occurredAt) {
		this.eventType = eventType;
		this.schemaVersion = schemaVersion;
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.data = data;
		this.occurredAt = occurredAt;
		this.nextAttemptAt = occurredAt;
	}

	/** Only after SQS has acknowledged this individual entry. */
	void markPublished(Instant now) {
		this.publishedAt = now;
		this.lastError = null;
	}

	/** Stays pending; retried after exponential backoff (2s, 4s, 8s, ... capped at 5 minutes). */
	void recordFailure(Instant now, String error) {
		this.attempts++;
		long seconds = attempts >= 30 ? MAX_BACKOFF.toSeconds()
				: Math.min(MAX_BACKOFF.toSeconds(), 1L << attempts);
		this.nextAttemptAt = now.plusSeconds(seconds);
		this.lastError = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
	}

	public UUID getId() {
		return id;
	}

	public String getEventType() {
		return eventType;
	}

	public int getSchemaVersion() {
		return schemaVersion;
	}

	public String getAggregateType() {
		return aggregateType;
	}

	public UUID getAggregateId() {
		return aggregateId;
	}

	public String getData() {
		return data;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public String getLastError() {
		return lastError;
	}

}
