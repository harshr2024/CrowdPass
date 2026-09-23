package com.crowdpass.ratelimit;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret base64-encoded HMAC key (at least 256 bits) for identities in Redis keys
 * @param ephemeralSecretAllowed generate a random key when {@code secret} is absent; local
 *     development and tests only
 * @param policies limits keyed by {@link RateLimitPolicy#keyName()}
 */
@ConfigurationProperties("crowdpass.rate-limit")
public record RateLimitProperties(String secret, boolean ephemeralSecretAllowed, Map<String, Limit> policies) {

	/** At most {@code requests} per fixed {@code window}. */
	public record Limit(int requests, Duration window) {
	}

}
