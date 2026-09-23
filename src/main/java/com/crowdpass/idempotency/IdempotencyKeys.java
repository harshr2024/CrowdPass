package com.crowdpass.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Validates the optional HTTP header and immediately replaces its raw value with a SHA-256 digest. */
@Component
public class IdempotencyKeys {

	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._~:-]{15,127}");

	public Optional<byte[]> validateAndHash(List<String> values) {
		if (values == null || values.isEmpty()) {
			return Optional.empty();
		}
		if (values.size() != 1 || !VALID.matcher(values.getFirst()).matches()) {
			throw new InvalidIdempotencyKeyException();
		}
		return Optional.of(sha256(values.getFirst().getBytes(StandardCharsets.US_ASCII)));
	}

	static byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

}
