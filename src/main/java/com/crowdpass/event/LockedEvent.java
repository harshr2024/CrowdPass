package com.crowdpass.event;

import java.time.Instant;

/**
 * Event columns read while holding the event row lock. Because no other seat-changing transaction
 * can run concurrently, {@link #getReservedCount()} is current for the rest of the transaction, as
 * long as the caller itself only changes it through the conditional capacity updates.
 */
public interface LockedEvent {

	String getStatus();

	int getCapacity();

	int getReservedCount();

	Instant getRegistrationOpenAt();

	Instant getRegistrationCloseAt();

	Instant getStartsAt();

	default boolean isPublished() {
		return EventStatus.PUBLISHED.name().equals(getStatus());
	}

}
