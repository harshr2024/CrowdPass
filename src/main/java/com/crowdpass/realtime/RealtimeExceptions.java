package com.crowdpass.realtime;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.crowdpass.exception.ApiException;

final class RealtimeConnectionLimitExceeded extends ApiException {
	RealtimeConnectionLimitExceeded() {
		super(HttpStatus.TOO_MANY_REQUESTS, "REALTIME_CONNECTION_LIMIT",
				"Too many realtime notification connections.");
	}
}

final class RealtimeAuthenticationExpired extends ApiException {
	RealtimeAuthenticationExpired() {
		super(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
	}

	@Override
	public HttpHeaders getHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		return headers;
	}
}

final class RealtimeUnavailable extends ApiException {
	RealtimeUnavailable() {
		super(HttpStatus.SERVICE_UNAVAILABLE, "REALTIME_UNAVAILABLE",
				"Realtime notifications are temporarily unavailable.");
	}
}
