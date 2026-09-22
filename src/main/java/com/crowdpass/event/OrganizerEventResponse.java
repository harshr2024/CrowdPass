package com.crowdpass.event;

import java.time.Instant;
import java.util.UUID;

/** Organizer's view of their own event, including lifecycle status and audit timestamps. */
public record OrganizerEventResponse(
		UUID id,
		String name,
		String description,
		EventStatus status,
		int capacity,
		int reservedCount,
		String timeZone,
		Instant registrationOpenAt,
		Instant registrationCloseAt,
		Instant startsAt,
		Instant endsAt,
		Instant cancelledAt,
		Instant createdAt,
		Instant updatedAt) {

	static OrganizerEventResponse from(Event event) {
		return new OrganizerEventResponse(
				event.getId(),
				event.getName(),
				event.getDescription(),
				event.getStatus(),
				event.getCapacity(),
				event.getReservedCount(),
				event.getTimeZone().getId(),
				event.getRegistrationOpenAt(),
				event.getRegistrationCloseAt(),
				event.getStartsAt(),
				event.getEndsAt(),
				event.getCancelledAt(),
				event.getCreatedAt(),
				event.getUpdatedAt());
	}

}
