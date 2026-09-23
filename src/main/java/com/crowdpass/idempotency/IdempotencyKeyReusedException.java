package com.crowdpass.idempotency;

import org.springframework.http.HttpStatus;

import com.crowdpass.exception.ApiException;

public class IdempotencyKeyReusedException extends ApiException {

	public IdempotencyKeyReusedException() {
		super(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
				"This Idempotency-Key was already used for a different request.");
	}

}
