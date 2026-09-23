package com.crowdpass.reservation;

import org.springframework.http.HttpStatus;

import com.crowdpass.exception.ApiException;

/** Domain outcomes of waitlist operations, each with a stable API error code. */
final class WaitlistExceptions {

	private WaitlistExceptions() {
	}

	static final class AlreadyWaitlisted extends ApiException {
		AlreadyWaitlisted() {
			super(HttpStatus.CONFLICT, "ALREADY_WAITLISTED", "You are already on the waitlist for this event.");
		}
	}

	static final class SeatAvailable extends ApiException {
		SeatAvailable() {
			super(HttpStatus.CONFLICT, "SEAT_AVAILABLE", "A seat is available; reserve it directly instead.");
		}
	}

	static final class WaitlistEntryNotFound extends ApiException {
		WaitlistEntryNotFound() {
			super(HttpStatus.NOT_FOUND, "WAITLIST_ENTRY_NOT_FOUND", "Waitlist entry not found.");
		}
	}

	static final class AlreadyPromoted extends ApiException {
		AlreadyPromoted() {
			super(HttpStatus.CONFLICT, "ALREADY_PROMOTED",
					"You were promoted from the waitlist; cancel the reservation to give up the seat.");
		}
	}

}
