package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import com.crowdpass.ConcurrentAttempts.Result;
import com.crowdpass.ratelimit.RateLimitExceededException;
import com.crowdpass.ratelimit.RateLimitPolicy;
import com.crowdpass.ratelimit.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/** Rate-limiter semantics against real Redis, with a controllable clock and low limits. */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest(properties = {
		"crowdpass.rate-limit.policies.login-ip.requests=5",
		"crowdpass.rate-limit.policies.login-account.requests=3",
		"crowdpass.rate-limit.policies.register-ip.requests=4",
		"crowdpass.rate-limit.policies.seat-mutation.requests=50" })
class RateLimiterIntegrationTest {

	private static final Instant WINDOW_START = MutableClock.Config.START;
	private static final Duration MICRO = Duration.ofNanos(1_000);

	@Autowired
	private RateLimiter rateLimiter;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private MutableClock clock;

	@Autowired
	private MeterRegistry meterRegistry;

	@BeforeEach
	void setUp() {
		redis.execute((RedisCallback<Object>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
		clock.set(WINDOW_START);
	}

	@Test
	void allowsUpToTheLimitAndRejectsTheFirstRequestOverIt() {
		for (int i = 0; i < 5; i++) {
			rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("203.0.113.7"));
		}

		assertRateLimited(() -> rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("203.0.113.7")), 60);
	}

	@Test
	void retryAfterIsTimeRemainingInWindowRoundedUp() {
		clock.set(WINDOW_START.plusSeconds(10));
		exhaust(RateLimitPolicy.LOGIN_IP, "198.51.100.1", 5);
		assertRateLimited(() -> rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("198.51.100.1")), 50);

		clock.set(WINDOW_START.plusMillis(59_001));
		assertRateLimited(() -> rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("198.51.100.1")), 1);
	}

	@Test
	void windowRollsOverDeterministicallyAtTheBoundary() {
		clock.set(WINDOW_START.plusSeconds(30));
		exhaust(RateLimitPolicy.LOGIN_IP, "203.0.113.9", 5);

		clock.set(WINDOW_START.plusSeconds(60).minus(MICRO));
		assertRateLimited(() -> rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("203.0.113.9")), 1);

		clock.set(WINDOW_START.plusSeconds(60));
		for (int i = 0; i < 5; i++) {
			rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("203.0.113.9"));
		}
		assertRateLimited(() -> rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("203.0.113.9")), 60);
	}

	@Test
	void firstIncrementSetsCleanupTtlOnceAndLaterIncrementsDoNotRefreshIt() {
		UUID user = UUID.randomUUID();
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, user);
		String key = singleKey();
		long firstTtl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);

		clock.set(WINDOW_START.plusSeconds(10));
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, user);
		long secondTtl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);

		// Window end (60s) + cleanup slack (60s), set by the first request only.
		assertThat(firstTtl).isBetween(119_000L, 120_000L);
		assertThat(secondTtl).as("TTL not re-derived from the later clock value (110s)").isGreaterThan(115_000L);
		assertThat(redis.opsForValue().get(key)).isEqualTo("2");
	}

	@Test
	void concurrentRequestsForOneIdentityAllowExactlyTheLimit() throws Exception {
		UUID user = UUID.randomUUID();
		List<Callable<String>> attempts = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			attempts.add(() -> {
				try {
					rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, user);
					return "ALLOWED";
				}
				catch (RateLimitExceededException ex) {
					return ex.getCode();
				}
			});
		}

		Result result = ConcurrentAttempts.run(200, attempts, () -> 0);

		assertThat(result.finished()).isTrue();
		assertThat(result.unexpected()).isEmpty();
		assertThat(result.count("ALLOWED")).isEqualTo(50);
		assertThat(result.count("RATE_LIMITED")).isEqualTo(150);
		assertThat(redis.opsForValue().get(singleKey())).isEqualTo("200");
	}

	@Test
	void identitiesAndPoliciesAreIndependent() {
		exhaust(RateLimitPolicy.LOGIN_IP, "203.0.113.10", 5);
		rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("203.0.113.11"));
		rateLimiter.checkClientIp(RateLimitPolicy.REGISTER_IP, ip("203.0.113.10"));

		for (int i = 0; i < 3; i++) {
			rateLimiter.checkAccount(RateLimitPolicy.LOGIN_ACCOUNT, "alice@example.com");
		}
		assertRateLimited(() -> rateLimiter.checkAccount(RateLimitPolicy.LOGIN_ACCOUNT, "alice@example.com"), 900);
		rateLimiter.checkAccount(RateLimitPolicy.LOGIN_ACCOUNT, "bob@example.com");

		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, UUID.randomUUID());
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, UUID.randomUUID());
	}

	@Test
	void ipv6AddressesInOneSlash64ShareALimit() {
		exhaust(RateLimitPolicy.LOGIN_IP, "2001:db8:1:2::1", 5);

		assertRateLimited(() -> rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("2001:db8:1:2:ffff::9")), 60);
		rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("2001:db8:1:3::1"));
	}

	@Test
	void redisKeysContainOnlyOpaqueIdentitiesAndNoHashTags() {
		rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("203.0.113.7"));
		rateLimiter.checkClientIp(RateLimitPolicy.REGISTER_IP, ip("2001:db8:1:2::1"));
		rateLimiter.checkAccount(RateLimitPolicy.LOGIN_ACCOUNT, "alice@example.com");
		UUID user = UUID.randomUUID();
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, user);

		Set<String> keys = redis.keys("*");

		assertThat(keys).hasSize(4).allSatisfy(key -> {
			assertThat(key).matches("rl:v1:(login-ip|login-account|register-ip|seat-mutation):[0-9a-f-]+:[0-9]+");
			// Only substrings that cannot occur in hex/UUID output, so the check is not flaky.
			assertThat(key).doesNotContain("{").doesNotContain("}")
					.doesNotContain("203.0.113.7").doesNotContain("2001:db8")
					.doesNotContain("alice").doesNotContain("example");
			assertThat(redis.opsForValue().get(key)).isEqualTo("1");
		});
		assertThat(keys).filteredOn(key -> !key.startsWith("rl:v1:seat-mutation:"))
				.allSatisfy(key -> assertThat(key.split(":")[3]).hasSize(32).matches("[0-9a-f]{32}"));
		assertThat(keys).filteredOn(key -> key.startsWith("rl:v1:seat-mutation:"))
				.singleElement().satisfies(key -> assertThat(key).contains(user.toString()));
	}

	@Test
	void decisionsAreCountedByPolicyAndOutcome() {
		double allowedBefore = decisions("login-ip", "allowed");
		double limitedBefore = decisions("login-ip", "limited");

		exhaust(RateLimitPolicy.LOGIN_IP, "192.0.2.50", 5);
		assertRateLimited(() -> rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, ip("192.0.2.50")), 60);

		assertThat(decisions("login-ip", "allowed") - allowedBefore).isEqualTo(5);
		assertThat(decisions("login-ip", "limited") - limitedBefore).isEqualTo(1);
	}

	private void exhaust(RateLimitPolicy policy, String address, int requests) {
		for (int i = 0; i < requests; i++) {
			rateLimiter.checkClientIp(policy, ip(address));
		}
	}

	private String singleKey() {
		Set<String> keys = redis.keys("rl:v1:*");
		assertThat(keys).hasSize(1);
		return keys.iterator().next();
	}

	private double decisions(String policy, String outcome) {
		var counter = meterRegistry.find("crowdpass.ratelimit.decisions").tags("policy", policy, "outcome", outcome)
				.counter();
		return counter == null ? 0 : counter.count();
	}

	private static MockHttpServletRequest ip(String address) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(address);
		return request;
	}

	private static void assertRateLimited(ThrowingCallable call, long expectedRetryAfter) {
		assertThatThrownBy(call).isInstanceOf(RateLimitExceededException.class).satisfies(ex -> {
			RateLimitExceededException limited = (RateLimitExceededException) ex;
			assertThat(limited.getCode()).isEqualTo("RATE_LIMITED");
			assertThat(limited.getRetryAfterSeconds()).isEqualTo(expectedRetryAfter);
		});
	}

}
