package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.crowdpass.ratelimit.RateLimitExceededException;
import com.crowdpass.ratelimit.RateLimitPolicy;
import com.crowdpass.ratelimit.RateLimiter;

/**
 * Redis is reachable but not responding (container paused). Each rate-limit call is bounded by the
 * 200ms command timeout and then fails open. After Redis resumes, limits are enforced again.
 */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest(properties = "crowdpass.rate-limit.policies.seat-mutation.requests=1")
class RedisHungFailOpenIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(RedisHungFailOpenIntegrationTest.class);

	@Autowired
	private RateLimiter rateLimiter;

	@Autowired
	private GenericContainer<?> redisContainer;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private MutableClock clock;

	@BeforeEach
	void setUp() {
		clock.set(MutableClock.Config.START);
		redis.execute((RedisCallback<Object>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
	}

	@Test
	void hungRedisFailsOpenAfterTheCommandTimeoutAndRecovers() {
		UUID user = UUID.randomUUID();
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, user);

		List<Long> latenciesMillis = new ArrayList<>();
		pause(true);
		try {
			for (int i = 0; i < 3; i++) {
				long start = System.nanoTime();
				rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, user);
				latenciesMillis.add((System.nanoTime() - start) / 1_000_000);
			}
		}
		finally {
			pause(false);
		}

		log.info("Redis paused: rate-limit call latencies (ms) = {}", latenciesMillis);
		assertThat(latenciesMillis).allSatisfy(ms -> assertThat(ms).isBetween(150L, 1_000L));

		UUID fresh = UUID.randomUUID();
		clock.advance(java.time.Duration.ofMinutes(1));
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, fresh);
		assertThatThrownBy(() -> rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, fresh))
				.as("limits enforced again after Redis resumes")
				.isInstanceOf(RateLimitExceededException.class);
	}

	private void pause(boolean paused) {
		var docker = DockerClientFactory.instance().client();
		if (paused) {
			docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
		}
		else {
			docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
		}
	}

}
