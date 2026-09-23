package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.crowdpass.auth.JwtTokenService;
import com.crowdpass.user.Role;

import tools.jackson.databind.json.JsonMapper;

/** Rate limits through the HTTP API: which requests count, and the 429 contract. */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"crowdpass.rate-limit.policies.login-ip.requests=8",
		"crowdpass.rate-limit.policies.login-account.requests=3",
		"crowdpass.rate-limit.policies.register-ip.requests=4",
		"crowdpass.rate-limit.policies.seat-mutation.requests=3" })
class RateLimitApiIntegrationTest {

	private static final String PASSWORD = "correct horse battery staple";

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JwtTokenService jwtTokenService;

	@Autowired
	private MutableClock clock;

	private ReservationTestData data;

	@BeforeEach
	void setUp() {
		data = new ReservationTestData(jdbcTemplate);
		data.reset();
		redis.execute((RedisCallback<Object>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
		clock.set(MutableClock.Config.START);
	}

	@Test
	void wrongPasswordsAndUnknownEmailsConsumeTheAccountAllowance() throws Exception {
		insertUser("alice@example.com");
		for (int i = 0; i < 3; i++) {
			assertThat(login("alice@example.com", "wrong password entirely").statusCode()).isEqualTo(401);
		}

		HttpResponse<String> limited = login("alice@example.com", PASSWORD);

		assertRateLimited(limited, "900");
		assertThat(login("bob@example.com", PASSWORD).statusCode()).as("other accounts unaffected").isEqualTo(401);
	}

	@Test
	void unknownEmailAttemptsConsumeTheIpAllowance() throws Exception {
		for (int i = 0; i < 8; i++) {
			assertThat(login("nobody" + i + "@example.com", PASSWORD).statusCode()).isEqualTo(401);
		}

		assertRateLimited(login("nobody-else@example.com", PASSWORD), "60");
	}

	@Test
	void malformedRequestsDoNotConsumeAllowance() throws Exception {
		insertUser("alice@example.com");
		for (int i = 0; i < 10; i++) {
			assertThat(send(HttpRequest.newBuilder(uri("/api/auth/login"))
					.POST(HttpRequest.BodyPublishers.ofString("{\"email\": ")).header("Content-Type", "application/json"),
					null).statusCode()).isEqualTo(400);
			assertThat(post("/api/auth/login", Map.of("email", "", "password", PASSWORD), null).statusCode())
					.isEqualTo(400);
		}

		assertThat(login("alice@example.com", PASSWORD).statusCode()).isEqualTo(200);
	}

	@Test
	void registrationIsLimitedPerIpAndLimitedRequestsCreateNoAccount() throws Exception {
		for (int i = 0; i < 4; i++) {
			assertThat(register("user" + i + "@example.com").statusCode()).isEqualTo(201);
		}

		assertRateLimited(register("user4@example.com"), "600");
		assertThat(jdbcTemplate.queryForObject("select count(*) from users", Integer.class)).isEqualTo(4);
	}

	@Test
	void seatMutationLimitIsSharedAcrossOperationsAndRejectsBeforeAnyStateChange() throws Exception {
		UUID event = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 10,
				MutableClock.Config.START.minus(Duration.ofHours(1)), MutableClock.Config.START.plus(Duration.ofDays(1)));
		UUID user = data.insertUser("USER");
		String token = jwtTokenService.issueAccessToken(user, Role.USER);

		String id = jsonMapper.readTree(post("/api/events/" + event + "/reservations", Map.of(), token).body())
				.path("id").asString();
		assertThat(post("/api/reservations/" + id + "/cancel", Map.of(), token).statusCode()).isEqualTo(200);
		assertThat(post("/api/events/" + event + "/waitlist/me/leave", Map.of(), token).statusCode()).isEqualTo(404);

		HttpResponse<String> limited = post("/api/events/" + event + "/reservations", Map.of(), token);

		assertRateLimited(limited, "60");
		assertThat(data.state(event).confirmed()).isZero();
		assertThat(data.state(event).reservedCount()).isZero();
		assertThat(post("/api/events/" + event + "/reservations", Map.of(),
				jwtTokenService.issueAccessToken(data.insertUser("USER"), Role.USER)).statusCode())
				.as("other users unaffected").isEqualTo(201);
	}

	@Test
	void unauthenticatedRequestsDoNotConsumeUserAllowanceAndLimitsResetNextWindow() throws Exception {
		UUID event = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 10,
				MutableClock.Config.START.minus(Duration.ofHours(1)), MutableClock.Config.START.plus(Duration.ofDays(1)));
		UUID user = data.insertUser("USER");
		String token = jwtTokenService.issueAccessToken(user, Role.USER);
		for (int i = 0; i < 5; i++) {
			assertThat(post("/api/events/" + event + "/waitlist", Map.of(), null).statusCode()).isEqualTo(401);
		}
		for (int i = 0; i < 3; i++) {
			assertThat(post("/api/events/" + event + "/waitlist", Map.of(), token).statusCode()).isEqualTo(409);
		}
		assertRateLimited(post("/api/events/" + event + "/waitlist", Map.of(), token), "60");

		clock.advance(Duration.ofSeconds(60));

		assertThat(post("/api/events/" + event + "/waitlist", Map.of(), token).statusCode()).isEqualTo(409);
	}

	private void assertRateLimited(HttpResponse<String> response, String retryAfter) {
		assertThat(response.statusCode()).isEqualTo(429);
		assertThat(response.headers().firstValue("Retry-After")).contains(retryAfter);
		@SuppressWarnings("unchecked")
		Map<String, Object> body = jsonMapper.readValue(response.body(), Map.class);
		assertThat(body).containsOnlyKeys("timestamp", "status", "error", "code", "message", "path", "requestId");
		assertThat(body).containsEntry("status", 429)
				.containsEntry("error", "Too Many Requests")
				.containsEntry("code", "RATE_LIMITED")
				.containsEntry("message", "Too many requests. Please try again later.");
		assertThat(body.get("requestId")).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
		assertThat(response.body()).doesNotContainIgnoringCase("redis").doesNotContain("rl:v1")
				.doesNotContain("login-ip").doesNotContain("login-account");
	}

	private void insertUser(String email) throws Exception {
		assertThat(register(email).statusCode()).isEqualTo(201);
		redis.execute((RedisCallback<Object>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
	}

	private HttpResponse<String> register(String email) throws Exception {
		return post("/api/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "User"), null);
	}

	private HttpResponse<String> login(String email, String password) throws Exception {
		return post("/api/auth/login", Map.of("email", email, "password", password), null);
	}

	private HttpResponse<String> post(String path, Map<String, ?> body, String bearerToken) throws Exception {
		return send(HttpRequest.newBuilder(uri(path))
				.POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
				.header("Content-Type", "application/json"), bearerToken);
	}

	private HttpResponse<String> send(HttpRequest.Builder request, String bearerToken) throws Exception {
		if (bearerToken != null) {
			request.header("Authorization", "Bearer " + bearerToken);
		}
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

}
