package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.crowdpass.ConcurrentAttempts.Result;
import com.crowdpass.ReservationTestData.EventState;
import com.crowdpass.auth.JwtTokenService;
import com.crowdpass.user.Role;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reservation endpoints end to end: security, controller, service, and PostgreSQL. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationApiIntegrationTest {

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JwtTokenService jwtTokenService;

	private ReservationTestData data;
	private UUID organizer;

	@BeforeEach
	void setUp() {
		data = new ReservationTestData(jdbcTemplate);
		data.reset();
		organizer = data.insertUser("ORGANIZER");
	}

	@Test
	void reserveReturns201WithLocationOfReadableResource() throws Exception {
		UUID event = openEvent(10);
		UUID user = data.insertUser("USER");

		HttpResponse<String> created = post("/api/events/" + event + "/reservations", token(user));

		assertThat(created.statusCode()).isEqualTo(201);
		JsonNode body = json(created);
		assertThat(body.path("status").asString()).isEqualTo("CONFIRMED");
		assertThat(body.path("eventId").asString()).isEqualTo(event.toString());
		assertThat(body.path("cancelledAt").isNull()).isTrue();
		String location = created.headers().firstValue("Location").orElseThrow();
		assertThat(location).isEqualTo("/api/reservations/" + body.path("id").asString());

		HttpResponse<String> fetched = get(location, token(user));
		assertThat(fetched.statusCode()).isEqualTo(200);
		assertThat(json(fetched)).isEqualTo(body);
	}

	@Test
	void otherUsersCannotReadOrCancelReservation() throws Exception {
		UUID event = openEvent(10);
		UUID owner = data.insertUser("USER");
		UUID other = data.insertUser("USER");
		String id = json(post("/api/events/" + event + "/reservations", token(owner))).path("id").asString();

		HttpResponse<String> read = get("/api/reservations/" + id, token(other));
		HttpResponse<String> cancel = post("/api/reservations/" + id + "/cancel", token(other));

		assertThat(read.statusCode()).isEqualTo(404);
		assertThat(json(read).path("code").asString()).isEqualTo("RESERVATION_NOT_FOUND");
		assertThat(cancel.statusCode()).isEqualTo(404);
		assertThat(data.reservationStatus(UUID.fromString(id))).isEqualTo("CONFIRMED");
	}

	@Test
	void cancelReturns200AndRepeatedCancelReturnsSameState() throws Exception {
		UUID event = openEvent(10);
		UUID user = data.insertUser("USER");
		String id = json(post("/api/events/" + event + "/reservations", token(user))).path("id").asString();

		HttpResponse<String> first = post("/api/reservations/" + id + "/cancel", token(user));
		HttpResponse<String> second = post("/api/reservations/" + id + "/cancel", token(user));

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(json(first).path("status").asString()).isEqualTo("CANCELLED");
		assertThat(json(second)).isEqualTo(json(first));
		assertThat(data.state(event).reservedCount()).isZero();
	}

	@Test
	void domainFailuresUseStandardErrorFormat() throws Exception {
		UUID event = openEvent(1);
		UUID holder = data.insertUser("USER");
		post("/api/events/" + event + "/reservations", token(holder));

		HttpResponse<String> full = post("/api/events/" + event + "/reservations", token(data.insertUser("USER")));
		HttpResponse<String> duplicate = post("/api/events/" + event + "/reservations", token(holder));
		HttpResponse<String> missing = post("/api/events/" + UUID.randomUUID() + "/reservations", token(holder));

		assertError(full, 409, "EVENT_FULL");
		assertError(duplicate, 409, "ALREADY_RESERVED");
		assertError(missing, 404, "EVENT_NOT_FOUND");
		assertThat(full.body() + duplicate.body()).doesNotContain("ux_reservations").doesNotContain("reserved_count")
				.doesNotContain("23505");
	}

	@Test
	void reservationEndpointsRequireAuthentication() throws Exception {
		UUID event = openEvent(10);

		assertThat(post("/api/events/" + event + "/reservations", null).statusCode()).isEqualTo(401);
		assertThat(get("/api/reservations/" + UUID.randomUUID(), null).statusCode()).isEqualTo(401);
		assertThat(post("/api/reservations/" + UUID.randomUUID() + "/cancel", null).statusCode()).isEqualTo(401);
		assertThat(data.state(event).reservedCount()).isZero();
	}

	@Test
	void malformedIdsAreRejected() throws Exception {
		UUID user = data.insertUser("USER");

		assertError(post("/api/events/not-a-uuid/reservations", token(user)), 400, "VALIDATION_FAILED");
		assertError(post("/api/reservations/not-a-uuid/cancel", token(user)), 400, "VALIDATION_FAILED");
	}

	@Test
	void concurrentHttpReservationsNeverOversell() throws Exception {
		UUID event = openEvent(20);
		List<UUID> users = data.insertUsers(100, "USER");
		List<Callable<String>> attempts = users.stream().<Callable<String>>map(user -> () -> {
			HttpResponse<String> response = post("/api/events/" + event + "/reservations", token(user));
			return response.statusCode() == 201 ? "201" : response.statusCode() + " " + json(response).path("code").asString();
		}).toList();

		Result result = ConcurrentAttempts.run(50, attempts, () -> 0);

		assertThat(result.finished()).isTrue();
		assertThat(result.unexpected()).isEmpty();
		assertThat(result.outcomes()).containsOnly(Map.entry("201", 20L), Map.entry("409 EVENT_FULL", 80L));
		EventState state = data.state(event);
		assertThat(state.confirmed()).isEqualTo(20);
		assertThat(state.reservedCount()).isEqualTo(20);
		assertThat(state.distinctConfirmedUsers()).isEqualTo(20);
	}

	private UUID openEvent(int capacity) {
		Instant now = Instant.now();
		return data.insertEvent(organizer, "PUBLISHED", capacity, now.minus(Duration.ofHours(1)),
				now.plus(Duration.ofDays(1)));
	}

	private String token(UUID user) {
		return jwtTokenService.issueAccessToken(user, Role.USER);
	}

	private void assertError(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		@SuppressWarnings("unchecked")
		Map<String, Object> body = jsonMapper.readValue(response.body(), Map.class);
		assertThat(body).containsOnlyKeys("timestamp", "status", "error", "code", "message", "path", "requestId");
		assertThat(body.get("code")).isEqualTo(code);
	}

	private HttpResponse<String> post(String path, String bearerToken) throws Exception {
		return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()), bearerToken);
	}

	private HttpResponse<String> get(String path, String bearerToken) throws Exception {
		return send(HttpRequest.newBuilder(uri(path)).GET(), bearerToken);
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

	private JsonNode json(HttpResponse<String> response) {
		return jsonMapper.readTree(response.body());
	}

}
