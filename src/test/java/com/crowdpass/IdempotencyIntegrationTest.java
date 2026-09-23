package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.crowdpass.auth.JwtTokenService;
import com.crowdpass.idempotency.IdempotencyCleanupService;
import com.crowdpass.idempotency.IdempotencyRecordStore;
import com.crowdpass.idempotency.ReservationRequestFingerprint;
import com.crowdpass.reservation.ReservationService;
import com.crowdpass.user.Role;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP idempotency behavior against real PostgreSQL and Redis. */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"crowdpass.rate-limit.policies.seat-mutation.requests=1000",
		"crowdpass.idempotency.retention=24h",
		"crowdpass.idempotency.cleanup.fixed-delay=24h",
		"crowdpass.idempotency.cleanup.batch-size=10" })
@ExtendWith(OutputCaptureExtension.class)
class IdempotencyIntegrationTest {

	private static final Instant NOW = MutableClock.Config.START;
	private static final Instant OPEN = NOW.minus(Duration.ofHours(1));
	private static final Instant CLOSE = NOW.plus(Duration.ofDays(9));
	private static final Instant STARTS = NOW.plus(Duration.ofDays(10));

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JwtTokenService jwtTokenService;

	@Autowired
	private MutableClock clock;

	@Autowired
	private IdempotencyCleanupService cleanupService;

	@Autowired
	private ReservationRequestFingerprint fingerprint;

	@MockitoSpyBean
	private ReservationService reservationService;

	@MockitoSpyBean
	private IdempotencyRecordStore recordStore;

	private ReservationTestData data;
	private UUID organizer;

	@BeforeEach
	void setUp() {
		data = new ReservationTestData(jdbc);
		data.reset();
		redis.execute((RedisCallback<Object>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
		clock.set(NOW);
		organizer = data.insertUser("ORGANIZER");
		clearInvocations(reservationService, recordStore);
	}

	@AfterEach
	void resetSpies() {
		reset(reservationService, recordStore);
	}

	@Test
	void sequentialReplayReturnsOriginalResponseWithFreshRequestId() throws Exception {
		UUID event = event(2);
		UUID user = data.insertUser("USER");
		String key = key("sequential");

		HttpResponse<String> first = reserve(event, user, key);
		HttpResponse<String> replay = reserve(event, user, key);

		assertSameCreation(first, replay);
		assertThat(requestId(first)).isNotEqualTo(requestId(replay));
		assertThat(data.state(event).confirmed()).isEqualTo(1);
		assertThat(recordCount()).isEqualTo(1);
		assertThat(completedCount()).isEqualTo(1);
		assertThat(rateCount(user)).isEqualTo(1);
		verify(reservationService, times(1)).reserve(event, user);
	}

	@Test
	void fiftySimultaneousSameKeyRequestsExecuteAndRateLimitOnce() throws Exception {
		UUID event = event(5);
		UUID user = data.insertUser("USER");
		String key = key("fifty");
		List<java.util.concurrent.Callable<String>> attempts = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			attempts.add(() -> creationSignature(reserve(event, user, key)));
		}

		ConcurrentAttempts.Result result = ConcurrentAttempts.run(50, attempts, () -> 0);

		assertThat(result.finished()).isTrue();
		assertThat(result.unexpected()).isEmpty();
		assertThat(result.outcomes()).hasSize(1).allSatisfy((response, count) -> {
			assertThat(response).startsWith("201|");
			assertThat(count).isEqualTo(50);
		});
		assertThat(data.state(event).confirmed()).isEqualTo(1);
		assertThat(recordCount()).isEqualTo(1);
		assertThat(completedCount()).isEqualTo(1);
		assertThat(rateCount(user)).isEqualTo(1);
		verify(reservationService, times(1)).reserve(event, user);
	}

	@Test
	void sameKeyForDifferentEventConflictsWithoutExecutionOrRateCharge() throws Exception {
		UUID firstEvent = event(2);
		UUID secondEvent = event(2);
		UUID user = data.insertUser("USER");
		String key = key("fingerprint");

		assertThat(reserve(firstEvent, user, key).statusCode()).isEqualTo(201);
		HttpResponse<String> conflict = reserve(secondEvent, user, key);

		assertError(conflict, 409, "IDEMPOTENCY_KEY_REUSED");
		assertThat(data.state(secondEvent).confirmed()).isZero();
		assertThat(recordCount()).isEqualTo(1);
		assertThat(rateCount(user)).isEqualTo(1);
		verify(reservationService, times(1)).reserve(any(UUID.class), any(UUID.class));
	}

	@Test
	void sameTextualKeyIsIndependentForDifferentUsers() throws Exception {
		UUID event = event(2);
		UUID first = data.insertUser("USER");
		UUID second = data.insertUser("USER");
		String key = key("shared");

		assertThat(reserve(event, first, key).statusCode()).isEqualTo(201);
		assertThat(reserve(event, second, key).statusCode()).isEqualTo(201);

		assertThat(data.state(event).confirmed()).isEqualTo(2);
		assertThat(recordCount()).isEqualTo(2);
		assertThat(rateCount(first)).isEqualTo(1);
		assertThat(rateCount(second)).isEqualTo(1);
	}

	@Test
	void differentKeysRacingForOneUserLeaveOnlySuccessfulRecord() throws Exception {
		UUID event = event(2);
		UUID user = data.insertUser("USER");
		List<java.util.concurrent.Callable<String>> attempts = List.of(
				() -> statusAndCode(reserve(event, user, key("race-a"))),
				() -> statusAndCode(reserve(event, user, key("race-b"))));

		ConcurrentAttempts.Result result = ConcurrentAttempts.run(2, attempts, () -> 0);

		assertThat(result.unexpected()).isEmpty();
		assertThat(result.outcomes()).containsOnly(Map.entry("201", 1L), Map.entry("409 ALREADY_RESERVED", 1L));
		assertThat(data.state(event).confirmed()).isEqualTo(1);
		assertThat(recordCount()).isEqualTo(1);
		assertThat(rateCount(user)).isEqualTo(2);
	}

	@Test
	void blockedRetryTakesOwnershipAfterFirstOwnerRollsBack() throws Exception {
		UUID event = event(1);
		UUID user = data.insertUser("USER");
		String key = key("rollback");
		CountDownLatch firstAtCompletion = new CountDownLatch(1);
		CountDownLatch releaseFailure = new CountDownLatch(1);
		AtomicInteger completions = new AtomicInteger();
		doAnswer(invocation -> {
			if (completions.getAndIncrement() == 0) {
				firstAtCompletion.countDown();
				if (!releaseFailure.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test did not release completion failure");
				}
				throw new IllegalStateException("simulated completion failure");
			}
			return invocation.callRealMethod();
		}).when(recordStore).complete(any(UUID.class), anyInt(), any(JsonNode.class), anyString(), any(Instant.class),
				any(Instant.class));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<HttpResponse<String>> first = executor.submit(() -> reserve(event, user, key));
			assertThat(firstAtCompletion.await(10, TimeUnit.SECONDS)).isTrue();
			Future<HttpResponse<String>> retry = executor.submit(() -> reserve(event, user, key));
			Thread.sleep(200);
			releaseFailure.countDown();

			assertError(first.get(20, TimeUnit.SECONDS), 500, "INTERNAL_ERROR");
			HttpResponse<String> succeeded = retry.get(20, TimeUnit.SECONDS);
			assertThat(succeeded.statusCode()).isEqualTo(201);
		}
		finally {
			releaseFailure.countDown();
			executor.shutdownNow();
		}
		assertThat(data.state(event).confirmed()).isEqualTo(1);
		assertThat(recordCount()).isEqualTo(1);
		assertThat(completedCount()).isEqualTo(1);
		assertThat(rateCount(user)).isEqualTo(2);
		verify(reservationService, times(2)).reserve(event, user);
	}

	@Test
	void committedStateReplaysWhenFirstHttpResponseIsIgnored() throws Exception {
		UUID event = event(1);
		UUID user = data.insertUser("USER");
		String key = key("lost-response");

		HttpResponse<String> ignored = reserve(event, user, key);
		UUID persistedReservation = jdbc.queryForObject(
				"select id from reservations where event_id = ? and status = 'CONFIRMED'", UUID.class, event);
		assertThat(completedCount()).isEqualTo(1);

		HttpResponse<String> replay = reserve(event, user, key);

		assertThat(replay.statusCode()).isEqualTo(201);
		assertThat(json(replay).path("id").asString()).isEqualTo(persistedReservation.toString());
		assertSameCreation(ignored, replay);
	}

	@Test
	void replayAfterCancellationUsesOriginalConfirmedSnapshot() throws Exception {
		UUID event = event(1);
		UUID user = data.insertUser("USER");
		String key = key("cancelled-resource");
		HttpResponse<String> created = reserve(event, user, key);
		String reservationId = json(created).path("id").asString();

		assertThat(post("/api/reservations/" + reservationId + "/cancel", user, List.of()).statusCode()).isEqualTo(200);
		HttpResponse<String> replay = reserve(event, user, key);
		HttpResponse<String> current = get("/api/reservations/" + reservationId, user);

		assertThat(json(replay).path("status").asString()).isEqualTo("CONFIRMED");
		assertThat(json(replay).path("cancelledAt").isNull()).isTrue();
		assertThat(json(current).path("status").asString()).isEqualTo("CANCELLED");
		assertSameCreation(created, replay);
	}

	@Test
	void replayDoesNotConsumeAnotherRateLimitAllowanceAndKeylessBehaviorIsUnchanged() throws Exception {
		UUID keyedEvent = event(2);
		UUID keyedUser = data.insertUser("USER");
		String key = key("rate-limit");

		assertThat(reserve(keyedEvent, keyedUser, key).statusCode()).isEqualTo(201);
		assertThat(reserve(keyedEvent, keyedUser, key).statusCode()).isEqualTo(201);
		assertThat(rateCount(keyedUser)).isEqualTo(1);

		UUID keylessEvent = event(2);
		UUID keylessUser = data.insertUser("USER");
		assertThat(reserve(keylessEvent, keylessUser, null).statusCode()).isEqualTo(201);
		assertError(reserve(keylessEvent, keylessUser, null), 409, "ALREADY_RESERVED");
		assertThat(rateCount(keylessUser)).isEqualTo(2);
	}

	@Test
	void rawKeyIsAbsentFromDatabaseAndLogs(CapturedOutput output) throws Exception {
		UUID event = event(1);
		UUID user = data.insertUser("USER");
		String canary = "canary-secret-key-1234567890";

		assertThat(reserve(event, user, canary).statusCode()).isEqualTo(201);

		byte[] stored = jdbc.queryForObject("select key_hash from idempotency_records", byte[].class);
		byte[] expected = MessageDigest.getInstance("SHA-256").digest(canary.getBytes(StandardCharsets.US_ASCII));
		assertThat(stored).hasSize(32).isEqualTo(expected);
		assertThat(output.getOut() + output.getErr()).doesNotContain(canary);
	}

	@Test
	void invalidAndDuplicateHeadersAreRejectedBeforeRedisAndSeatMutation() throws Exception {
		UUID event = event(2);
		UUID user = data.insertUser("USER");

		assertError(reserve(event, user, "short"), 400, "INVALID_IDEMPOTENCY_KEY");
		assertError(reserve(event, user, "contains whitespace 12345"), 400, "INVALID_IDEMPOTENCY_KEY");
		HttpResponse<String> duplicate = post("/api/events/" + event + "/reservations", user,
				List.of(key("duplicate-a"), key("duplicate-b")));
		assertError(duplicate, 400, "INVALID_IDEMPOTENCY_KEY");

		assertThat(recordCount()).isZero();
		assertThat(data.state(event).confirmed()).isZero();
		assertThat(redis.keys("rl:v1:seat-mutation:*")).isEmpty();
		verify(reservationService, times(0)).reserve(any(UUID.class), any(UUID.class));
	}

	@Test
	void expiredRecordReplaysUntilCleanupThenKeyMayExecuteAsNew() throws Exception {
		UUID event = event(1);
		UUID user = data.insertUser("USER");
		String key = key("expiry");
		HttpResponse<String> first = reserve(event, user, key);
		String firstReservation = json(first).path("id").asString();
		assertThat(post("/api/reservations/" + firstReservation + "/cancel", user, List.of()).statusCode()).isEqualTo(200);
		clock.advance(Duration.ofHours(25));
		assertThat(jdbc.queryForObject("select expires_at <= ? from idempotency_records", Boolean.class,
				clock.instant().atOffset(java.time.ZoneOffset.UTC))).isTrue();

		HttpResponse<String> beforeCleanup = reserve(event, user, key);
		assertThat(json(beforeCleanup).path("id").asString()).isEqualTo(firstReservation);
		assertThat(data.state(event).confirmed()).isZero();

		assertThat(cleanupService.cleanupOnce()).isEqualTo(1);
		assertThat(recordCount()).isZero();
		HttpResponse<String> afterCleanup = reserve(event, user, key);
		assertThat(afterCleanup.statusCode()).isEqualTo(201);
		assertThat(json(afterCleanup).path("id").asString()).isNotEqualTo(firstReservation);
		assertThat(data.state(event).confirmed()).isEqualTo(1);
		assertThat(recordCount()).isEqualTo(1);
	}

	@Test
	void committedInProgressRecordIsAnInternalInvariantViolation(CapturedOutput output) throws Exception {
		UUID event = event(1);
		UUID user = data.insertUser("USER");
		String key = key("committed-in-progress");
		byte[] keyHash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII));
		assertThat(recordStore.tryClaim(user, keyHash, fingerprint.create(event), NOW)).isPresent();

		HttpResponse<String> response = reserve(event, user, key);

		assertError(response, 500, "INTERNAL_ERROR");
		assertThat(response.body()).doesNotContain("IN_PROGRESS");
		assertThat(jdbc.queryForObject("select state from idempotency_records", String.class)).isEqualTo("IN_PROGRESS");
		assertThat(data.state(event).confirmed()).isZero();
		assertThat(rateCount(user)).isZero();
		verify(reservationService, times(0)).reserve(any(UUID.class), any(UUID.class));
		assertThat(output.getOut() + output.getErr()).contains("Committed idempotency record is not completed")
				.doesNotContain(key);
	}

	private UUID event(int capacity) {
		return data.insertEvent(organizer, "PUBLISHED", capacity, OPEN, CLOSE, STARTS);
	}

	private HttpResponse<String> reserve(UUID event, UUID user, String key) throws Exception {
		return post("/api/events/" + event + "/reservations", user, key == null ? List.of() : List.of(key));
	}

	private HttpResponse<String> post(String path, UUID user, List<String> keys) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user));
		keys.forEach(key -> request.header("Idempotency-Key", key));
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, UUID user) throws Exception {
		return httpClient.send(HttpRequest.newBuilder(uri(path)).GET()
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private String token(UUID user) {
		return jwtTokenService.issueAccessToken(user, Role.USER);
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private JsonNode json(HttpResponse<String> response) {
		return jsonMapper.readTree(response.body());
	}

	private static String key(String suffix) {
		return "phase7-key-" + suffix + "-1234567890";
	}

	private long recordCount() {
		return jdbc.queryForObject("select count(*) from idempotency_records", Long.class);
	}

	private long completedCount() {
		return jdbc.queryForObject("select count(*) from idempotency_records where state = 'COMPLETED'", Long.class);
	}

	private long rateCount(UUID user) {
		Set<String> keys = redis.keys("rl:v1:seat-mutation:" + user + ":*");
		if (keys == null || keys.isEmpty()) {
			return 0;
		}
		assertThat(keys).hasSize(1);
		return Long.parseLong(redis.opsForValue().get(keys.iterator().next()));
	}

	private void assertSameCreation(HttpResponse<String> first, HttpResponse<String> replay) {
		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(replay.statusCode()).isEqualTo(201);
		assertThat(replay.headers().firstValue(HttpHeaders.LOCATION))
				.isEqualTo(first.headers().firstValue(HttpHeaders.LOCATION));
		assertThat(json(replay)).isEqualTo(json(first));
	}

	private void assertError(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		assertThat(json(response).path("code").asString()).isEqualTo(code);
	}

	private String creationSignature(HttpResponse<String> response) {
		JsonNode body = json(response);
		return String.join("|", Integer.toString(response.statusCode()),
				response.headers().firstValue(HttpHeaders.LOCATION).orElse(""), body.path("id").asString(),
				body.path("eventId").asString(), body.path("status").asString(), body.path("createdAt").asString(),
				body.path("cancelledAt").asString());
	}

	private String statusAndCode(HttpResponse<String> response) {
		return response.statusCode() == 201 ? "201" : response.statusCode() + " " + json(response).path("code").asString();
	}

	private static String requestId(HttpResponse<String> response) {
		return response.headers().firstValue("X-Request-Id").orElseThrow();
	}

}
