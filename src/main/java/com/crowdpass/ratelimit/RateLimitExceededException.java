package com.crowdpass.ratelimit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.crowdpass.exception.ApiException;

/** The same code and message for every policy, so responses do not reveal which limit applied. */
public class RateLimitExceededException extends ApiException {

	private final long retryAfterSeconds;

	RateLimitExceededException(long retryAfterSeconds) {
		super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Please try again later.");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}

	@Override
	public HttpHeaders getHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
		return headers;
	}

}
