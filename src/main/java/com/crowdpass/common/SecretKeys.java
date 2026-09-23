package com.crowdpass.common;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves base64-encoded secrets from configuration. A missing secret fails startup unless a
 * throwaway random key is explicitly allowed (local development and tests only).
 */
public final class SecretKeys {

	private static final Logger log = LoggerFactory.getLogger(SecretKeys.class);

	private SecretKeys() {
	}

	/**
	 * @param property configuration property name, e.g. {@code crowdpass.jwt.secret}
	 * @param environmentVariable the environment variable that normally supplies it
	 */
	public static byte[] resolve(String property, String environmentVariable, String base64Secret,
			boolean ephemeralAllowed, int minBytes) {
		if (base64Secret == null || base64Secret.isBlank()) {
			if (!ephemeralAllowed) {
				throw new IllegalStateException(property + " (" + environmentVariable
						+ ") must be set to a base64-encoded key of at least " + minBytes + " bytes");
			}
			log.warn("{} is not configured; generated an ephemeral key that will not survive a restart.", property);
			byte[] random = new byte[minBytes];
			new SecureRandom().nextBytes(random);
			return random;
		}
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(base64Secret.strip());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(property + " is not valid base64");
		}
		if (decoded.length < minBytes) {
			throw new IllegalStateException(property + " must decode to at least " + minBytes + " bytes");
		}
		return decoded;
	}

}
