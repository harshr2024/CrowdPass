package com.crowdpass.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.crowdpass.ratelimit.RateLimitProperties.Limit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Fixed-window rate limiter. Each decision captures the current time once and derives the window,
 * the Redis key, the cleanup TTL, and Retry-After from that instant. The counter is incremented by a
 * single-key Lua script, so the increment and its expiry are applied atomically across all
 * application instances.
 *
 * <p>Failure policy: if Redis errors or times out, the request is allowed (fail open) and the event
 * is counted and logged. Redis is never a source of truth; no correctness guarantee depends on it.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

	/**
	 * Key format: {@code rl:v1:<policy>:<identity>:<windowStartEpochSeconds>}. Keys must never contain
	 * '{' or '}': Redis Cluster treats a {...} substring as a hash tag and would pin every matching key
	 * to one slot. Each script call touches a single key, so there is no reason to co-locate keys.
	 */
	static final String KEY_PREFIX = "rl:v1:";

	/** Keeps a key briefly past its window so clock skew between instances cannot resurrect a count. */
	static final Duration CLEANUP_SLACK = Duration.ofMinutes(1);

	/** KEYS[1] = window key, ARGV[1] = TTL in milliseconds. Returns the count including this request. */
	static final RedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then
			  redis.call('PEXPIRE', KEYS[1], ARGV[1])
			end
			return count
			""", Long.class);

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
	private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

	private final StringRedisTemplate redis;
	private final Clock clock;
	private final RateLimitIdentities identities;
	private final ClientIpResolver clientIpResolver;
	private final Map<RateLimitPolicy, Limit> limits;
	private final MeterRegistry meterRegistry;
	private final Timer redisLatency;
	private final AtomicLong lastFailureLogNanos = new AtomicLong(System.nanoTime() - FAILURE_LOG_INTERVAL_NANOS);
	private final AtomicLong suppressedFailureLogs = new AtomicLong();

	RateLimiter(StringRedisTemplate redis, Clock clock, RateLimitIdentities identities,
			ClientIpResolver clientIpResolver, RateLimitProperties properties, MeterRegistry meterRegistry) {
		this.redis = redis;
		this.clock = clock;
		this.identities = identities;
		this.clientIpResolver = clientIpResolver;
		this.limits = validatedLimits(properties);
		this.meterRegistry = meterRegistry;
		this.redisLatency = Timer.builder("crowdpass.ratelimit.redis.latency")
				.description("Latency of rate-limit Redis calls")
				.register(meterRegistry);
	}

	public void checkClientIp(RateLimitPolicy policy, HttpServletRequest request) {
		check(policy, identities.clientIp(clientIpResolver.resolve(request)));
	}

	public void checkAccount(RateLimitPolicy policy, String canonicalEmail) {
		check(policy, identities.email(canonicalEmail));
	}

	public void checkUser(RateLimitPolicy policy, UUID userId) {
		check(policy, userId.toString());
	}

	private void check(RateLimitPolicy policy, String identity) {
		Instant now = clock.instant();
		Limit limit = limits.get(policy);
		long windowMillis = limit.window().toMillis();
		long nowMillis = now.toEpochMilli();
		long windowStart = Math.floorDiv(nowMillis, windowMillis) * windowMillis;
		long windowEnd = windowStart + windowMillis;
		String key = key(policy, identity, windowStart);
		long ttlMillis = windowEnd - nowMillis + CLEANUP_SLACK.toMillis();

		Long count;
		Timer.Sample sample = Timer.start(meterRegistry);
		try {
			count = redis.execute(INCREMENT_SCRIPT, List.of(key), Long.toString(ttlMillis));
		}
		catch (RuntimeException ex) {
			failOpen(policy, ex);
			return;
		}
		finally {
			sample.stop(redisLatency);
		}
		if (count == null) {
			failOpen(policy, null);
			return;
		}
		if (count > limit.requests()) {
			record(policy, "limited");
			log.debug("Rate limit exceeded (policy={})", policy.keyName());
			throw new RateLimitExceededException(retryAfterSeconds(now, windowEnd));
		}
		record(policy, "allowed");
	}

	static String key(RateLimitPolicy policy, String identity, long windowStartMillis) {
		String key = KEY_PREFIX + policy.keyName() + ":" + identity + ":" + Math.floorDiv(windowStartMillis, 1000);
		if (key.indexOf('{') >= 0 || key.indexOf('}') >= 0) {
			throw new IllegalStateException("Rate-limit keys must not contain Redis Cluster hash-tag braces");
		}
		return key;
	}

	/** Whole seconds until the window ends, rounded up, at least 1. */
	static long retryAfterSeconds(Instant now, long windowEndMillis) {
		long remainingNanos = Duration.between(now, Instant.ofEpochMilli(windowEndMillis)).toNanos();
		long seconds = Math.ceilDiv(remainingNanos, 1_000_000_000L);
		return Math.max(1, seconds);
	}

	private void failOpen(RateLimitPolicy policy, RuntimeException failure) {
		record(policy, "failed_open");
		long nowNanos = System.nanoTime();
		long last = lastFailureLogNanos.get();
		if (nowNanos - last >= FAILURE_LOG_INTERVAL_NANOS && lastFailureLogNanos.compareAndSet(last, nowNanos)) {
			log.warn("Rate limiting unavailable; allowing request (policy={}, error={}, suppressedWarnings={})",
					policy.keyName(), failure == null ? "null result" : failure.getClass().getSimpleName(),
					suppressedFailureLogs.getAndSet(0));
		}
		else {
			suppressedFailureLogs.incrementAndGet();
		}
	}

	private void record(RateLimitPolicy policy, String outcome) {
		meterRegistry.counter("crowdpass.ratelimit.decisions", "policy", policy.keyName(), "outcome", outcome)
				.increment();
	}

	private static Map<RateLimitPolicy, Limit> validatedLimits(RateLimitProperties properties) {
		Map<RateLimitPolicy, Limit> limits = new EnumMap<>(RateLimitPolicy.class);
		for (RateLimitPolicy policy : RateLimitPolicy.values()) {
			Limit limit = properties.policies() == null ? null : properties.policies().get(policy.keyName());
			if (limit == null || limit.requests() < 1 || limit.window() == null
					|| limit.window().compareTo(Duration.ofSeconds(1)) < 0 || limit.window().toMillis() % 1000 != 0) {
				throw new IllegalStateException("crowdpass.rate-limit.policies." + policy.keyName()
						+ " must define requests >= 1 and a window of whole seconds >= 1s");
			}
			limits.put(policy, limit);
		}
		return limits;
	}

}
