package com.crowdpass.idempotency;

import org.springframework.http.HttpStatus;

import com.crowdpass.exception.ApiException;

public class InvalidIdempotencyKeyException extends ApiException {

	public InvalidIdempotencyKeyException() {
		super(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
				"Idempotency-Key must be a single value of 16 to 128 allowed ASCII characters.");
	}

}
