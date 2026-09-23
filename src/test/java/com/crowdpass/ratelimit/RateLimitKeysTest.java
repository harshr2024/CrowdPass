package com.crowdpass.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure functions of the rate limiter: key format, Retry-After rounding, and IP normalization. */
class RateLimitKeysTest {

	private static final long WINDOW_START = Instant.parse("2031-03-01T12:00:00Z").toEpochMilli();

	@Test
	void keyHasDocumentedFormatWithoutHashTags() {
		String key = RateLimiter.key(RateLimitPolicy.LOGIN_IP, "0123456789abcdef0123456789abcdef", WINDOW_START);

		assertThat(key).isEqualTo("rl:v1:login-ip:0123456789abcdef0123456789abcdef:" + WINDOW_START / 1000);
		assertThat(key).doesNotContain("{").doesNotContain("}");
	}

	@ParameterizedTest
	@ValueSource(strings = { "{tag}", "a{b", "c}d" })
	void keyRejectsRedisClusterHashTagBraces(String identity) {
		assertThatThrownBy(() -> RateLimiter.key(RateLimitPolicy.SEAT_MUTATION, identity, WINDOW_START))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("hash-tag");
	}

	@ParameterizedTest(name = "{0} before window end -> {1}s")
	@CsvSource({
			"PT60S,        60",
			"PT50S,        50",
			"PT0.999S,     1",
			"PT0.000001S,  1",
			"PT0S,         1" })
	void retryAfterRoundsUpToWholeSecondsAndIsAtLeastOne(java.time.Duration remaining, long expected) {
		long windowEnd = WINDOW_START + 60_000;
		Instant now = Instant.ofEpochMilli(windowEnd).minus(remaining);

		assertThat(RateLimiter.retryAfterSeconds(now, windowEnd)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"203.0.113.7,                    v4:203.0.113.7",
			"::ffff:203.0.113.7,             v4:203.0.113.7",
			"2001:db8:1:2:aaaa:bbbb:cccc:dddd, v6:2001:db8:1:2:0:0:0:0/64",
			"2001:db8:1:2::1,                v6:2001:db8:1:2:0:0:0:0/64",
			"2001:db8:1:3::1,                v6:2001:db8:1:3:0:0:0:0/64",
			"fe80::1%eth0,                   v6:fe80:0:0:0:0:0:0:0/64",
			"not-an-ip.example,              other:not-an-ip.example" })
	void normalizesIpv4ExactlyAndIpv6ToSlash64(String remoteAddress, String expected) {
		assertThat(RateLimitIdentities.normalizeIp(remoteAddress)).isEqualTo(expected);
	}

}
