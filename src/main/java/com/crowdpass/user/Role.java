package com.crowdpass.user;

/**
 * Roles are additive capabilities: every role can act as an attendee. ORGANIZER adds event
 * management and ADMIN adds platform administration.
 */
public enum Role {
	USER,
	ORGANIZER,
	ADMIN
}
