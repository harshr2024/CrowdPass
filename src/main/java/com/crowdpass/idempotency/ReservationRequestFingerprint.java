package com.crowdpass.idempotency;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** Versioned semantic fingerprint for POST reservations.create with its empty request body. */
@Component
public class ReservationRequestFingerprint {

	private static final String PREFIX = "crowdpass-request:v1\0POST\0reservations.create\0";
	private static final String EMPTY_BODY_SHA256 =
			"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	public byte[] create(UUID eventId) {
		String canonical = PREFIX + eventId.toString() + "\0" + EMPTY_BODY_SHA256;
		return IdempotencyKeys.sha256(canonical.getBytes(StandardCharsets.UTF_8));
	}

}
