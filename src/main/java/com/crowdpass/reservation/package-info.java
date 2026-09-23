/**
 * Reservations and the waitlist: one consistency boundary for seat allocation. Owns the capacity
 * invariant (no overselling), FIFO waitlist promotion, and the rule that no user is both CONFIRMED
 * and WAITING for the same event.
 *
 * <p>Lock-order rule for every transaction that changes reservations or waitlist entries: lock the
 * event row first. See {@link com.crowdpass.reservation.ReservationService}.
 *
 * <p>Dependency direction: this package depends on {@code reservation.waitlist} (the waitlist
 * persistence model), never the reverse.
 */
package com.crowdpass.reservation;
