package com.crowdpass.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Public view of an event. Instants are absolute (UTC); {@code timeZone} is the IANA zone for
 * rendering event-local times. {@code availableSeats} is a point-in-time snapshot for display.
 */
public record EventResponse(
		UUID id,
		String name,
		String description,
		int capacity,
		int availableSeats,
		String timeZone,
		Instant registrationOpenAt,
		Instant registrationCloseAt,
		Instant startsAt,
		Instant endsAt) {

	static EventResponse from(Event event) {
		return new EventResponse(
				event.getId(),
				event.getName(),
				event.getDescription(),
				event.getCapacity(),
				event.getCapacity() - event.getReservedCount(),
				event.getTimeZone().getId(),
				event.getRegistrationOpenAt(),
				event.getRegistrationCloseAt(),
				event.getStartsAt(),
				event.getEndsAt());
	}

}
