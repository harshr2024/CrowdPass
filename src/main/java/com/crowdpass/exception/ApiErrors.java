package com.crowdpass.exception;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.crowdpass.common.RequestIdFilter;

/** Builds {@link ApiError} bodies for both MVC exception handling and the security filter chain. */
@Component
public class ApiErrors {

	private final Clock clock;

	public ApiErrors(Clock clock) {
		this.clock = clock;
	}

	public ApiError create(HttpStatus status, String code, String message, String path) {
		return new ApiError(Instant.now(clock), status.value(), status.getReasonPhrase(), code, message, path,
				MDC.get(RequestIdFilter.MDC_KEY));
	}

}
