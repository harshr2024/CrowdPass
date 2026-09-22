package com.crowdpass.user;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.crowdpass.exception.ApiException;

/**
 * A validly signed token whose subject no longer exists is treated as unauthenticated, with the
 * same body and bearer challenge as filter-chain authentication failures.
 */
public class AuthenticatedUserNotFoundException extends ApiException {

	public AuthenticatedUserNotFoundException() {
		super(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
	}

	@Override
	public HttpHeaders getHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		return headers;
	}

}
