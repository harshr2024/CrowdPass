package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crowdpass.auth.JwtTokenService;
import com.crowdpass.user.Role;
import com.crowdpass.user.User;

import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import({ TestcontainersConfiguration.class, EventApiIntegrationTest.FailingEndpointConfiguration.class })
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class EventApiIntegrationTest {

	private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private JwtTokenService jwtTokenService;

	private UUID organizerId;

	@BeforeEach
	void resetData() {
		jdbcTemplate.execute("truncate waitlist_entries, reservations, events, users");
		organizerId = UUID.randomUUID();
		jdbcTemplate.update("""
				insert into users (id, email, password_hash, display_name, role, created_at, updated_at)
				values (?, 'organizer@example.com', 'hash', 'Organizer', 'ORGANIZER', ?, ?)
				""", organizerId, utc(NOW.minus(30, ChronoUnit.DAYS)), utc(NOW.minus(30, ChronoUnit.DAYS)));
	}

	@Test
	void listsOnlyPublishedEventsThatHaveNotEndedInStartOrder() {
		UUID ongoing = insertEvent("PUBLISHED", NOW.minus(1, ChronoUnit.HOURS), NOW.plus(2, ChronoUnit.HOURS));
		UUID tomorrow = insertEvent("PUBLISHED", NOW.plus(1, ChronoUnit.DAYS));
		UUID nextWeek = insertEvent("PUBLISHED", NOW.plus(7, ChronoUnit.DAYS));
		insertEvent("PUBLISHED", NOW.minus(3, ChronoUnit.DAYS), NOW.minus(2, ChronoUnit.DAYS));
		insertEvent("DRAFT", NOW.plus(2, ChronoUnit.DAYS));
		insertEvent("CANCELLED", NOW.plus(3, ChronoUnit.DAYS));

		JsonNode page = getJson("/api/events");

		assertThat(ids(page)).containsExactly(ongoing, tomorrow, nextWeek);
		assertThat(page.path("totalElements").asLong()).isEqualTo(3);
	}

	@Test
	void breaksStartTimeTiesById() {
		Instant sameStart = NOW.plus(1, ChronoUnit.DAYS);
		UUID higherId = UUID.fromString("ffffffff-0000-7000-8000-000000000000");
		UUID lowerId = UUID.fromString("00000000-0000-7000-8000-000000000000");
		insertEvent(higherId, "PUBLISHED", sameStart, sameStart.plus(1, ChronoUnit.HOURS), 100, 0);
		insertEvent(lowerId, "PUBLISHED", sameStart, sameStart.plus(1, ChronoUnit.HOURS), 100, 0);

		assertThat(ids(getJson("/api/events"))).containsExactly(lowerId, higherId);
	}

	@Test
	void paginatesWithStableNonOverlappingPages() {
		List<UUID> expected = new ArrayList<>();
		for (int day = 1; day <= 5; day++) {
			expected.add(insertEvent("PUBLISHED", NOW.plus(day, ChronoUnit.DAYS)));
		}

		JsonNode first = getJson("/api/events?page=0&size=2");
		JsonNode second = getJson("/api/events?page=1&size=2");
		JsonNode third = getJson("/api/events?page=2&size=2");
		JsonNode beyond = getJson("/api/events?page=3&size=2");

		List<UUID> paged = new ArrayList<>();
		paged.addAll(ids(first));
		paged.addAll(ids(second));
		paged.addAll(ids(third));
		assertThat(paged).containsExactlyElementsOf(expected);
		assertThat(first.path("page").asInt()).isZero();
		assertThat(first.path("size").asInt()).isEqualTo(2);
		assertThat(first.path("totalElements").asLong()).isEqualTo(5);
		assertThat(first.path("totalPages").asInt()).isEqualTo(3);
		assertThat(ids(beyond)).isEmpty();
		assertThat(beyond.path("totalElements").asLong()).isEqualTo(5);
	}

	@Test
	void appliesDefaultPagination() {
		JsonNode page = getJson("/api/events");

		assertThat(page.path("page").asInt()).isZero();
		assertThat(page.path("size").asInt()).isEqualTo(20);
	}

	@ParameterizedTest
	@CsvSource({
			"size=0,     size",
			"size=101,   size",
			"page=-1,    page",
			"size=abc,   size",
			"page=1.5,   page" })
	void rejectsInvalidPagination(String query, String parameter) throws Exception {
		HttpResponse<String> response = get("/api/events?" + query);

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode body = jsonMapper.readTree(response.body());
		assertThat(body.path("code").asString()).isEqualTo("VALIDATION_FAILED");
		assertThat(body.path("message").asString()).contains("'" + parameter + "'");
	}

	@Test
	void returnsPublishedEventWithInstantsAndTimeZone() {
		Instant starts = Instant.parse("2031-06-01T02:00:00Z");
		UUID id = UUID.randomUUID();
		insertEvent(id, "PUBLISHED", starts, starts.plus(3, ChronoUnit.HOURS), 10, 3);

		JsonNode event = getJson("/api/events/" + id);

		assertThat(event.path("id").asString()).isEqualTo(id.toString());
		assertThat(event.path("name").asString()).isEqualTo("Event " + id);
		assertThat(event.path("capacity").asInt()).isEqualTo(10);
		assertThat(event.path("availableSeats").asInt()).isEqualTo(7);
		assertThat(event.path("timeZone").asString()).isEqualTo("America/Los_Angeles");
		assertThat(event.path("startsAt").asString()).isEqualTo("2031-06-01T02:00:00Z");
		assertThat(event.path("endsAt").asString()).isEqualTo("2031-06-01T05:00:00Z");
		assertThat(event.path("registrationOpenAt").asString()).isEqualTo("2031-05-22T02:00:00Z");
		assertThat(event.path("registrationCloseAt").asString()).isEqualTo("2031-06-01T01:00:00Z");
		assertThat(event.has("organizer")).isFalse();
		assertThat(event.has("status")).isFalse();
	}

	@Test
	void returnsEndedPublishedEventById() {
		UUID ended = insertEvent("PUBLISHED", NOW.minus(3, ChronoUnit.DAYS), NOW.minus(2, ChronoUnit.DAYS));

		assertThat(getJson("/api/events/" + ended).path("id").asString()).isEqualTo(ended.toString());
	}

	@ParameterizedTest
	@ValueSource(strings = { "DRAFT", "CANCELLED", "NONEXISTENT" })
	void hidesNonPublishedAndMissingEventsBehindSame404(String status) throws Exception {
		UUID id = status.equals("NONEXISTENT") ? UUID.randomUUID()
				: insertEvent(status, NOW.plus(1, ChronoUnit.DAYS));

		HttpResponse<String> response = get("/api/events/" + id);

		assertThat(response.statusCode()).isEqualTo(404);
		JsonNode body = jsonMapper.readTree(response.body());
		assertThat(body.path("code").asString()).isEqualTo("EVENT_NOT_FOUND");
		assertThat(body.path("message").asString()).isEqualTo("Event not found.");
		assertThat(body.path("path").asString()).isEqualTo("/api/events/" + id);
	}

	@Test
	void rejectsMalformedEventId() throws Exception {
		HttpResponse<String> response = get("/api/events/not-a-uuid");

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode body = jsonMapper.readTree(response.body());
		assertThat(body.path("code").asString()).isEqualTo("VALIDATION_FAILED");
		assertThat(body.path("message").asString()).isEqualTo("Parameter 'id' has an invalid value.");
	}

	@Test
	void errorResponsesHaveStandardShapeAndCarryRequestId() throws Exception {
		HttpResponse<String> response = get("/api/events/" + UUID.randomUUID());

		@SuppressWarnings("unchecked")
		Map<String, Object> body = jsonMapper.readValue(response.body(), Map.class);
		assertThat(body).containsOnlyKeys("timestamp", "status", "error", "code", "message", "path", "requestId");
		assertThat(body.get("status")).isEqualTo(404);
		assertThat(body.get("error")).isEqualTo("Not Found");
		assertThat(Instant.parse((String) body.get("timestamp"))).isCloseTo(Instant.now(), within(60));
		String headerRequestId = response.headers().firstValue("X-Request-Id").orElseThrow();
		assertThat(body.get("requestId")).isEqualTo(headerRequestId);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
	}

	@Test
	void reusesWellFormedClientRequestIdAndReplacesMalformedOne() throws Exception {
		HttpResponse<String> wellFormed = get("/api/events/not-a-uuid", "client-abc-123");
		HttpResponse<String> malformed = get("/api/events/not-a-uuid", "x".repeat(65));

		assertThat(wellFormed.headers().firstValue("X-Request-Id")).contains("client-abc-123");
		assertThat(jsonMapper.readTree(wellFormed.body()).path("requestId").asString()).isEqualTo("client-abc-123");
		String replaced = malformed.headers().firstValue("X-Request-Id").orElseThrow();
		assertThat(UUID.fromString(replaced)).isNotNull();
	}

	@Test
	void unknownRouteAndUnsupportedMethodRequireAuthenticationFirst() throws Exception {
		assertThat(get("/api/does-not-exist").statusCode()).isEqualTo(401);
		assertThat(send(HttpRequest.newBuilder(uri("/api/events")).POST(HttpRequest.BodyPublishers.ofString("{}"))
				.header("Content-Type", "application/json")).statusCode()).isEqualTo(401);
	}

	@Test
	void unknownRouteAndUnsupportedMethodUseStandardFormatWhenAuthenticated() throws Exception {
		HttpResponse<String> unknown = send(HttpRequest.newBuilder(uri("/api/does-not-exist")).GET()
				.header("Authorization", "Bearer " + userToken()));
		HttpResponse<String> post = send(HttpRequest.newBuilder(uri("/api/events"))
				.POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + userToken()));

		assertThat(unknown.statusCode()).isEqualTo(404);
		assertThat(jsonMapper.readTree(unknown.body()).path("code").asString()).isEqualTo("NOT_FOUND");
		assertThat(post.statusCode()).isEqualTo(405);
		assertThat(jsonMapper.readTree(post.body()).path("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");
	}

	@Test
	void publicEndpointRejectsInvalidBearerToken() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/events")).GET()
				.header("Authorization", "Bearer not.a.jwt"));

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(jsonMapper.readTree(response.body()).path("code").asString()).isEqualTo("UNAUTHENTICATED");
	}

	@Test
	void unexpectedErrorsDoNotLeakInternalDetails() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(uri(FailingEndpointConfiguration.PATH)).GET()
				.header("Authorization", "Bearer " + userToken()));

		assertThat(response.statusCode()).isEqualTo(500);
		JsonNode body = jsonMapper.readTree(response.body());
		assertThat(body.path("code").asString()).isEqualTo("INTERNAL_ERROR");
		assertThat(body.path("message").asString()).isEqualTo("An unexpected error occurred.");
		assertThat(response.body())
				.doesNotContain("uq_users_email")
				.doesNotContain("insert into")
				.doesNotContain("DataIntegrityViolation")
				.doesNotContain("at com.crowdpass");
	}

	@Test
	void listingDoesNotLoadOrganizersAndUsesBoundedQueries() {
		for (int day = 1; day <= 3; day++) {
			insertEvent("PUBLISHED", NOW.plus(day, ChronoUnit.DAYS));
		}
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		JsonNode page = getJson("/api/events?size=2");

		assertThat(ids(page)).hasSize(2);
		// One page query plus one count query, independent of the number of events returned.
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
		assertThat(statistics.getEntityStatistics(User.class.getName()).getLoadCount()).isZero();
		assertThat(statistics.getEntityStatistics(User.class.getName()).getFetchCount()).isZero();
	}

	private UUID insertEvent(String status, Instant startsAt) {
		return insertEvent(status, startsAt, startsAt.plus(3, ChronoUnit.HOURS));
	}

	private UUID insertEvent(String status, Instant startsAt, Instant endsAt) {
		return insertEvent(UUID.randomUUID(), status, startsAt, endsAt, 100, 0);
	}

	private UUID insertEvent(UUID id, String status, Instant startsAt, Instant endsAt, int capacity,
			int reservedCount) {
		Instant createdAt = NOW.minus(30, ChronoUnit.DAYS);
		jdbcTemplate.update("""
				insert into events (id, organizer_id, name, capacity, reserved_count, status, time_zone,
				    registration_open_at, registration_close_at, starts_at, ends_at, cancelled_at,
				    created_at, updated_at)
				values (?, ?, ?, ?, ?, ?, 'America/Los_Angeles', ?, ?, ?, ?, ?, ?, ?)
				""",
				id, organizerId, "Event " + id, capacity, reservedCount, status,
				utc(startsAt.minus(10, ChronoUnit.DAYS)), utc(startsAt.minus(1, ChronoUnit.HOURS)),
				utc(startsAt), utc(endsAt), status.equals("CANCELLED") ? utc(createdAt) : null,
				utc(createdAt), utc(createdAt));
		return id;
	}

	private JsonNode getJson(String path) {
		try {
			HttpResponse<String> response = get(path);
			assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
			return jsonMapper.readTree(response.body());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private HttpResponse<String> get(String path) throws Exception {
		return httpClient.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String requestId) throws Exception {
		return httpClient.send(HttpRequest.newBuilder(uri(path)).header("X-Request-Id", requestId).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private String userToken() {
		return jwtTokenService.issueAccessToken(organizerId, Role.USER);
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private static List<UUID> ids(JsonNode page) {
		List<UUID> ids = new ArrayList<>();
		for (JsonNode item : page.path("items")) {
			ids.add(UUID.fromString(item.path("id").asString()));
		}
		return ids;
	}

	private static java.time.OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static org.assertj.core.data.TemporalUnitOffset within(long seconds) {
		return new org.assertj.core.data.TemporalUnitWithinOffset(seconds, ChronoUnit.SECONDS);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FailingEndpointConfiguration {

		static final String PATH = "/test/failure";

		@Bean
		FailingController failingController() {
			return new FailingController();
		}

	}

	@RestController
	static class FailingController {

		@GetMapping(FailingEndpointConfiguration.PATH)
		String fail() {
			throw new DataIntegrityViolationException(
					"could not execute statement [insert into users ...] constraint [uq_users_email]");
		}

	}

}
