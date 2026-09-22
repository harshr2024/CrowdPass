package com.crowdpass.reservation;

import org.springframework.http.HttpStatus;

import com.crowdpass.exception.ApiException;

/** Domain outcomes of reservation operations, each with a stable API error code. */
final class ReservationExceptions {

	private ReservationExceptions() {
	}

	static final class EventFull extends ApiException {
		EventFull() {
			super(HttpStatus.CONFLICT, "EVENT_FULL", "The event has reached capacity.");
		}
	}

	static final class AlreadyReserved extends ApiException {
		AlreadyReserved() {
			super(HttpStatus.CONFLICT, "ALREADY_RESERVED", "You already have an active reservation for this event.");
		}
	}

	static final class RegistrationNotOpen extends ApiException {
		RegistrationNotOpen() {
			super(HttpStatus.CONFLICT, "REGISTRATION_NOT_OPEN", "Registration for this event has not opened yet.");
		}
	}

	static final class RegistrationClosed extends ApiException {
		RegistrationClosed() {
			super(HttpStatus.CONFLICT, "REGISTRATION_CLOSED", "Registration for this event has closed.");
		}
	}

	static final class ReservationNotFound extends ApiException {
		ReservationNotFound() {
			super(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "Reservation not found.");
		}
	}

	static final class CancellationClosed extends ApiException {
		CancellationClosed() {
			super(HttpStatus.CONFLICT, "CANCELLATION_CLOSED",
					"The reservation can no longer be cancelled because the event has started.");
		}
	}

}
