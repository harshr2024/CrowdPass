package com.crowdpass.reservation;

import java.time.Instant;
import java.util.UUID;

import com.crowdpass.reservation.waitlist.WaitlistEntry;
import com.crowdpass.reservation.waitlist.WaitlistStatus;

/**
 * @param position 1-based place among currently WAITING entries; present only while WAITING. A
 *     snapshot that may change immediately.
 * @param waitlistClosed true once the event has started or is no longer published, after which a
 *     WAITING entry can no longer be promoted
 */
public record WaitlistEntryResponse(
		UUID id,
		UUID eventId,
		WaitlistStatus status,
		Long position,
		boolean waitlistClosed,
		Instant joinedAt,
		Instant promotedAt,
		Instant leftAt,
		UUID reservationId) {

	static WaitlistEntryResponse from(WaitlistEntry entry, Long position, boolean waitlistClosed) {
		return new WaitlistEntryResponse(entry.getId(), entry.getEvent().getId(), entry.getStatus(), position,
				waitlistClosed, entry.getCreatedAt(), entry.getPromotedAt(), entry.getLeftAt(),
				entry.getReservationId());
	}

}
