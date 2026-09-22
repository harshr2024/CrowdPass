package com.crowdpass.exception;

import java.time.Instant;

/** The standard error body returned by every API endpoint. */
public record ApiError(
		Instant timestamp,
		int status,
		String error,
		String code,
		String message,
		String path,
		String requestId) {
}
