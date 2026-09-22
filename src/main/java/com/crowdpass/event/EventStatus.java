package com.crowdpass.event;

/**
 * Lifecycle status. Whether registration is open is derived from status plus the registration
 * window, never stored.
 */
public enum EventStatus {
	DRAFT,
	PUBLISHED,
	CANCELLED
}
