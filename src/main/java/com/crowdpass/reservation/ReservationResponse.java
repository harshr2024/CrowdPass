package com.crowdpass.reservation;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(UUID id, UUID eventId, ReservationStatus status, Instant createdAt,
		Instant cancelledAt) {

	static ReservationResponse from(Reservation reservation) {
		return new ReservationResponse(reservation.getId(), reservation.getEvent().getId(), reservation.getStatus(),
				reservation.getCreatedAt(), reservation.getCancelledAt());
	}

}
