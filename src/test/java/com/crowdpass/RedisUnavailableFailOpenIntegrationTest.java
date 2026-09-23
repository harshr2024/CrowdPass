package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.crowdpass.ConcurrentAttempts.Result;
import com.crowdpass.auth.JwtTokenService;
import com.crowdpass.user.Role;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis is unreachable (connection refused). With every limit set to one request, requests beyond
 * it succeed: rate limiting fails open, the application stays healthy, and reservation correctness
 * (which never depends on Redis) is unchanged.
 */
@Import(RedisUnavailableFailOpenIntegrationTest.PostgresOnly.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"crowdpass.rate-limit.policies.login-ip.requests=1",
		"crowdpass.rate-limit.policies.login-account.requests=1",
		"crowdpass.rate-limit.policies.register-ip.requests=1",
		"crowdpass.rate-limit.policies.seat-mutation.requests=1" })
class RedisUnavailableFailOpenIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(RedisUnavailableFailOpenIntegrationTest.class);
	private static final String PASSWORD = "correct horse battery staple";

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JwtTokenService jwtTokenService;

	@Autowired
	private MeterRegistry meterRegistry;

	private ReservationTestData data;

	@TestConfiguration(proxyBeanMethods = false)
	static class PostgresOnly {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE));
		}

	}

	@DynamicPropertySource
	static void unreachableRedis(DynamicPropertyRegistry registry) throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		registry.add("spring.data.redis.host", () -> "127.0.0.1");
		registry.add("spring.data.redis.port", () -> closedPort);
	}

	@BeforeEach
	void setUp() {
		data = new ReservationTestData(jdbcTemplate);
		data.reset();
	}

	@Test
	void authenticationEndpointsFailOpenWithinTheTimeout() throws Exception {
		double failedOpenBefore = failedOpen();
		List<Long> latenciesMillis = new ArrayList<>();

		for (int i = 0; i < 3; i++) {
			long start = System.nanoTime();
			assertThat(register("user" + i + "@example.com").statusCode()).isEqualTo(201);
			latenciesMillis.add((System.nanoTime() - start) / 1_000_000);
		}
		for (int i = 0; i < 3; i++) {
			long start = System.nanoTime();
			assertThat(login("user0@example.com", PASSWORD).statusCode()).isEqualTo(200);
			latenciesMillis.add((System.nanoTime() - start) / 1_000_000);
		}

		log.info("Redis unreachable: register/login latencies (ms) = {}", latenciesMillis);
		assertThat(latenciesMillis).allSatisfy(ms -> assertThat(ms).isLessThan(2_000));
		assertThat(failedOpen() - failedOpenBefore).as("register 3 + login 3x2 checks").isEqualTo(9);
	}

	@Test
	void reservationCorrectnessIsIndependentOfRedis() throws Exception {
		Instant now = Instant.now();
		UUID event = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 20, now.minus(Duration.ofHours(1)),
				now.plus(Duration.ofDays(1)));
		List<UUID> users = data.insertUsers(100, "USER");
		List<Callable<String>> attempts = new ArrayList<>();
		for (UUID user : users) {
			String token = jwtTokenService.issueAccessToken(user, Role.USER);
			attempts.add(() -> {
				HttpResponse<String> response = post("/api/events/" + event + "/reservations", Map.of(), token);
				return response.statusCode() == 201 ? "201"
						: response.statusCode() + " " + jsonMapper.readTree(response.body()).path("code").asString();
			});
		}
		UUID repeat = users.getFirst();
		String repeatToken = jwtTokenService.issueAccessToken(repeat, Role.USER);

		Result result = ConcurrentAttempts.run(50, attempts, () -> 0);
		HttpResponse<String> overLimit = post("/api/events/" + event + "/reservations", Map.of(), repeatToken);

		assertThat(result.unexpected()).isEmpty();
		assertThat(result.outcomes()).containsOnly(Map.entry("201", 20L), Map.entry("409 EVENT_FULL", 80L));
		assertThat(overLimit.statusCode()).as("second seat mutation beyond limit 1 is not rate limited")
				.isIn(409);
		assertThat(data.invariantViolations(event)).isEmpty();
		assertThat(data.state(event).confirmed()).isEqualTo(20);
	}

	@Test
	void applicationStaysHealthy() throws Exception {
		HttpResponse<String> health = httpClient.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(health.statusCode()).isEqualTo(200);
		assertThat(health.body()).contains("\"UP\"");
	}

	private double failedOpen() {
		return meterRegistry.find("crowdpass.ratelimit.decisions").tag("outcome", "failed_open").counters().stream()
				.mapToDouble(counter -> counter.count()).sum();
	}

	private HttpResponse<String> register(String email) throws Exception {
		return post("/api/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "User"), null);
	}

	private HttpResponse<String> login(String email, String password) throws Exception {
		return post("/api/auth/login", Map.of("email", email, "password", password), null);
	}

	private HttpResponse<String> post(String path, Map<String, ?> body, String bearerToken) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
				.POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
				.header("Content-Type", "application/json");
		if (bearerToken != null) {
			request.header("Authorization", "Bearer " + bearerToken);
		}
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

}
