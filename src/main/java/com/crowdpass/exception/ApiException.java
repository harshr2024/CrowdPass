package com.crowdpass.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Base for exceptions that map to a specific HTTP status and stable error code. Domain modules
 * extend it, so the central handler never depends on any domain module. The message is returned
 * to clients and must not contain internal details.
 */
public abstract class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	protected ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	/** Additional response headers, e.g. an authentication challenge. */
	public HttpHeaders getHeaders() {
		return HttpHeaders.EMPTY;
	}

}
