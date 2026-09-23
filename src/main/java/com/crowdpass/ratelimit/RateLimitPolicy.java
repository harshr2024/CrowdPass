package com.crowdpass.ratelimit;

/** Each policy's limit is configured under {@code crowdpass.rate-limit.policies.<keyName>}. */
public enum RateLimitPolicy {

	LOGIN_IP("login-ip"),
	LOGIN_ACCOUNT("login-account"),
	REGISTER_IP("register-ip"),
	/** Shared by reserve, cancel, waitlist join, and waitlist leave. */
	SEAT_MUTATION("seat-mutation");

	private final String keyName;

	RateLimitPolicy(String keyName) {
		this.keyName = keyName;
	}

	public String keyName() {
		return keyName;
	}

}
