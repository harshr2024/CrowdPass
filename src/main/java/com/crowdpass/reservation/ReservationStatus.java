package com.crowdpass.reservation;

/** Only {@link #CONFIRMED} consumes event capacity. */
public enum ReservationStatus {
	CONFIRMED,
	CANCELLED
}
