package com.crowdpass.event;

import org.springframework.http.HttpStatus;

import com.crowdpass.exception.ApiException;

/**
 * Thrown when an event does not exist or is not visible to the caller. Both cases produce the
 * same response so clients cannot probe for unpublished events.
 */
public class EventNotFoundException extends ApiException {

	public EventNotFoundException() {
		super(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event not found.");
	}

}
