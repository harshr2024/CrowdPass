/**
 * Reservations: claiming and cancelling seats. Owns the capacity invariant (no overselling).
 *
 * <p>Lock-order rule for every transaction that changes reservations, including future waitlist
 * promotion: lock the event row first, then change reservation rows. See
 * {@link com.crowdpass.reservation.ReservationService}.
 */
package com.crowdpass.reservation;
