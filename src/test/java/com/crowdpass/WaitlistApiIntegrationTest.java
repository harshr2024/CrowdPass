package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.crowdpass.auth.JwtTokenService;
import com.crowdpass.user.Role;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Waitlist endpoints end to end: security, controller, service, and PostgreSQL. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WaitlistApiIntegrationTest {

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
	private UUID event;
	private UUID holder;
	private String holderReservation;

	@BeforeEach
	void setUp() throws Exception {
		data = new ReservationTestData(jdbcTemplate);
		data.reset();
		Instant now = Instant.now();
		event = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 1, now.minus(Duration.ofHours(1)),
				now.plus(Duration.ofDays(1)));
		holder = data.insertUser("USER");
		holderReservation = json(post("/api/events/" + event + "/reservations", token(holder))).path("id").asString();
	}

	@Test
	void joinReturns201WithLocationOfCallersEntry() throws Exception {
		UUID waiter = data.insertUser("USER");

		HttpResponse<String> joined = post("/api/events/" + event + "/waitlist", token(waiter));

		assertThat(joined.statusCode()).isEqualTo(201);
		String location = joined.headers().firstValue("Location").orElseThrow();
		assertThat(location).isEqualTo("/api/events/" + event + "/waitlist/me");
		JsonNode body = json(joined);
		assertThat(body.path("status").asString()).isEqualTo("WAITING");
		assertThat(body.path("position").asLong()).isEqualTo(1);
		assertThat(body.path("waitlistClosed").asBoolean()).isFalse();
		assertThat(body.has("queueSeq")).isFalse();
		assertThat(json(get(location, token(waiter)))).isEqualTo(body);
	}

	@Test
	void cancellationPromotesWaiterWhoThenOwnsTheReservation() throws Exception {
		UUID waiter = data.insertUser("USER");
		post("/api/events/" + event + "/waitlist", token(waiter));

		assertThat(post("/api/reservations/" + holderReservation + "/cancel", token(holder)).statusCode())
				.isEqualTo(200);

		JsonNode entry = json(get("/api/events/" + event + "/waitlist/me", token(waiter)));
		assertThat(entry.path("status").asString()).isEqualTo("PROMOTED");
		assertThat(entry.path("position").isNull()).isTrue();
		String reservationId = entry.path("reservationId").asString();
		HttpResponse<String> seat = get("/api/reservations/" + reservationId, token(waiter));
		assertThat(seat.statusCode()).isEqualTo(200);
		assertThat(json(seat).path("status").asString()).isEqualTo("CONFIRMED");
		assertThat(get("/api/reservations/" + reservationId, token(holder)).statusCode()).isEqualTo(404);
		assertThat(data.invariantViolations(event)).isEmpty();
	}

	@Test
	void leaveReturns200AndIsIdempotent() throws Exception {
		UUID waiter = data.insertUser("USER");
		post("/api/events/" + event + "/waitlist", token(waiter));

		HttpResponse<String> first = post("/api/events/" + event + "/waitlist/me/leave", token(waiter));
		HttpResponse<String> second = post("/api/events/" + event + "/waitlist/me/leave", token(waiter));

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(json(first).path("status").asString()).isEqualTo("LEFT");
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(json(second)).isEqualTo(json(first));
	}

	@Test
	void domainFailuresUseStandardErrorFormat() throws Exception {
		UUID waiter = data.insertUser("USER");
		post("/api/events/" + event + "/waitlist", token(waiter));

		assertError(post("/api/events/" + event + "/waitlist", token(waiter)), 409, "ALREADY_WAITLISTED");
		assertError(post("/api/events/" + event + "/waitlist", token(holder)), 409, "ALREADY_RESERVED");
		assertError(get("/api/events/" + event + "/waitlist/me", token(data.insertUser("USER"))), 404,
				"WAITLIST_ENTRY_NOT_FOUND");
		assertError(post("/api/events/" + UUID.randomUUID() + "/waitlist", token(waiter)), 404, "EVENT_NOT_FOUND");

		post("/api/reservations/" + holderReservation + "/cancel", token(holder));
		assertError(post("/api/events/" + event + "/waitlist/me/leave", token(waiter)), 409, "ALREADY_PROMOTED");
	}

	@Test
	void joiningWhileSeatIsFreeReturnsSeatAvailable() throws Exception {
		post("/api/reservations/" + holderReservation + "/cancel", token(holder));

		assertError(post("/api/events/" + event + "/waitlist", token(data.insertUser("USER"))), 409, "SEAT_AVAILABLE");
	}

	@Test
	void waitlistEndpointsRequireAuthentication() throws Exception {
		assertThat(post("/api/events/" + event + "/waitlist", null).statusCode()).isEqualTo(401);
		assertThat(get("/api/events/" + event + "/waitlist/me", null).statusCode()).isEqualTo(401);
		assertThat(post("/api/events/" + event + "/waitlist/me/leave", null).statusCode()).isEqualTo(401);
	}

	private String token(UUID user) {
		return jwtTokenService.issueAccessToken(user, Role.USER);
	}

	private void assertError(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
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
