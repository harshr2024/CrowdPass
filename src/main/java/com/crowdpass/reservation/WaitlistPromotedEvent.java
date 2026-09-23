package com.crowdpass.reservation;

import java.util.UUID;

/**
 * Event body written to the outbox when a waiting user is promoted into a freed seat. Contains
 * identifiers only; consumers derive anything presentational at read time.
 */
public record WaitlistPromotedEvent(UUID userId, UUID eventId, UUID reservationId, UUID waitlistEntryId) {

	public static final String TYPE = "WAITLIST_PROMOTED";
	public static final int VERSION = 1;
	public static final String AGGREGATE_TYPE = "waitlist_entry";

}
