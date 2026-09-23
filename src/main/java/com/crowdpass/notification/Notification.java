package com.crowdpass.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read model; rows are inserted by {@link NotificationRepository#insertIfAbsent} only. */
@Entity
@Table(name = "notifications")
public class Notification {

	@Id
	private UUID id;

	@Column(name = "source_event_id", nullable = false)
	private UUID sourceEventId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private NotificationType type;

	@Column(name = "event_id", nullable = false)
	private UUID eventId;

	@Column(name = "reservation_id", nullable = false)
	private UUID reservationId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "read_at")
	private Instant readAt;

	protected Notification() {
	}

	public UUID getId() {
		return id;
	}

	public UUID getSourceEventId() {
		return sourceEventId;
	}

	public UUID getUserId() {
		return userId;
	}

	public NotificationType getType() {
		return type;
	}

	public UUID getEventId() {
		return eventId;
	}

	public UUID getReservationId() {
		return reservationId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getReadAt() {
		return readAt;
	}

}
