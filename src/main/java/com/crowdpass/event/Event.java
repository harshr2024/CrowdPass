package com.crowdpass.event;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

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
@Table(name = "events")
public class Event {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "organizer_id", nullable = false)
	private User organizer;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(columnDefinition = "text")
	private String description;

	@Column(nullable = false)
	private int capacity;

	/**
	 * Owned by the database: defaults to 0 on insert and is changed only by the reservation
	 * module's conditional atomic UPDATE. Excluding it from INSERT and UPDATE statements prevents
	 * a stale entity from overwriting the live count.
	 */
	@Column(name = "reserved_count", nullable = false, insertable = false, updatable = false)
	private int reservedCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private EventStatus status;

	@Column(name = "time_zone", nullable = false, length = 64)
	private String timeZone;

	@Column(name = "registration_open_at", nullable = false)
	private Instant registrationOpenAt;

	@Column(name = "registration_close_at", nullable = false)
	private Instant registrationCloseAt;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Event() {
	}

	/** Creates a new event in {@link EventStatus#DRAFT}. */
	public Event(User organizer, String name, String description, int capacity, ZoneId timeZone,
			Instant registrationOpenAt, Instant registrationCloseAt, Instant startsAt, Instant endsAt,
			Instant now) {
		this.organizer = organizer;
		this.name = name;
		this.description = description;
		this.capacity = capacity;
		this.status = EventStatus.DRAFT;
		this.timeZone = timeZone.getId();
		this.registrationOpenAt = registrationOpenAt;
		this.registrationCloseAt = registrationCloseAt;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public User getOrganizer() {
		return organizer;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public int getCapacity() {
		return capacity;
	}

	/**
	 * The count as of when this entity was loaded. It is not refreshed by the atomic UPDATE, so
	 * capacity decisions must never be based on this value.
	 */
	public int getReservedCount() {
		return reservedCount;
	}

	public EventStatus getStatus() {
		return status;
	}

	public ZoneId getTimeZone() {
		return ZoneId.of(timeZone);
	}

	public Instant getRegistrationOpenAt() {
		return registrationOpenAt;
	}

	public Instant getRegistrationCloseAt() {
		return registrationCloseAt;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
