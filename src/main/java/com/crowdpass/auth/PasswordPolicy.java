package com.crowdpass.auth;

import java.nio.charset.StandardCharsets;

/**
 * Length-only policy (no composition rules; spaces allowed). The maximum is BCrypt's input limit
 * in bytes, not characters, because longer input would be silently truncated.
 */
final class PasswordPolicy {

	static final int MIN_LENGTH = 15;
	static final int MAX_UTF8_BYTES = 72;

	private PasswordPolicy() {
	}

	static boolean exceedsMaxBytes(String password) {
		return password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES;
	}

}
