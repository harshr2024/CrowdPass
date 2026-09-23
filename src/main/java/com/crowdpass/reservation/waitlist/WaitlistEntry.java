package com.crowdpass.reservation.waitlist;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;

import com.crowdpass.event.Event;
import com.crowdpass.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A user's place on an event's waitlist. State transitions are performed by conditional UPDATE
 * statements under the event row lock, so this entity has no mutators.
 */
@Entity
@Table(name = "waitlist_entries")
public class WaitlistEntry {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_id", nullable = false)
	private Event event;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	/** Database-assigned ordering token (not a position). */
	@Generated
	@Column(name = "queue_seq", insertable = false, updatable = false)
	private Long queueSeq;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private WaitlistStatus status;

	@Column(name = "reservation_id")
	private UUID reservationId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "promoted_at")
	private Instant promotedAt;

	@Column(name = "left_at")
	private Instant leftAt;

	protected WaitlistEntry() {
	}

	/** Creates a {@link WaitlistStatus#WAITING} entry. */
	public WaitlistEntry(Event event, User user, Instant now) {
		this.event = event;
		this.user = user;
		this.status = WaitlistStatus.WAITING;
		this.createdAt = now;
	}

	public UUID getId() {
		return id;
	}

	public Event getEvent() {
		return event;
	}

	public User getUser() {
		return user;
	}

	public Long getQueueSeq() {
		return queueSeq;
	}

	public WaitlistStatus getStatus() {
		return status;
	}

	public UUID getReservationId() {
		return reservationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getPromotedAt() {
		return promotedAt;
	}

	public Instant getLeftAt() {
		return leftAt;
	}

}
