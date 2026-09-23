/**
 * Waitlist persistence model: entries, statuses, and queries. Part of the reservation consistency
 * boundary; the transactions that change waitlist state live in
 * {@link com.crowdpass.reservation}, which depends on this package, never the reverse.
 */
package com.crowdpass.reservation.waitlist;
