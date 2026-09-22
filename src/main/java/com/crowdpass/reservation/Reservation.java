package com.crowdpass.reservation;

import java.time.Instant;
import java.util.UUID;

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

@Entity
@Table(name = "reservations")
public class Reservation {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_id", nullable = false)
	private Event event;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReservationStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	protected Reservation() {
	}

	/** Creates a {@link ReservationStatus#CONFIRMED} reservation. */
	public Reservation(Event event, User user, Instant now) {
		this.event = event;
		this.user = user;
		this.status = ReservationStatus.CONFIRMED;
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

	public ReservationStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}

}
