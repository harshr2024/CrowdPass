package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.crowdpass.auth.JwtTokenService;
import com.crowdpass.user.Role;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

	private static final String VALID_PASSWORD = "correct horse battery staple";
	private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

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
	private JwtEncoder jwtEncoder;

	@Autowired
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void resetData() {
		jdbcTemplate.execute("truncate waitlist_entries, reservations, events, users");
	}

	// ---- Registration ----

	@Test
	void registersCanonicalUserWithHashedPassword() throws Exception {
		HttpResponse<String> response = post("/api/auth/register",
				Map.of("email", "  Alice@Example.COM ", "password", VALID_PASSWORD, "displayName", " Alice "));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(response.headers().firstValue("Location")).contains("/api/users/me");
		Map<String, Object> body = jsonMap(response);
		assertThat(body).containsOnlyKeys("id", "email", "displayName", "role", "createdAt");
		assertThat(body).containsEntry("email", "alice@example.com")
				.containsEntry("displayName", "Alice")
				.containsEntry("role", "USER");
		assertThat(response.body()).doesNotContain(VALID_PASSWORD).doesNotContain("$2a$");

		Map<String, Object> row = jdbcTemplate.queryForMap("select role, password_hash from users where id = ?",
				UUID.fromString((String) body.get("id")));
		assertThat(row.get("role")).isEqualTo("USER");
		assertThat((String) row.get("password_hash")).startsWith("{bcrypt}$2a$10$");
	}

	@ParameterizedTest
	@ValueSource(strings = { "role", "passwordHash", "id" })
	void rejectsUnsupportedRegistrationFieldsWithoutCreatingUser(String field) throws Exception {
		Map<String, Object> body = registration("mallory@example.com");
		body.put(field, field.equals("role") ? "ADMIN" : "x");

		HttpResponse<String> response = post("/api/auth/register", body);

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode error = json(response);
		assertThat(error.path("code").asString()).isEqualTo("VALIDATION_FAILED");
		assertThat(error.path("message").asString()).isEqualTo("Unknown field '" + field + "'.");
		assertThat(jdbcTemplate.queryForObject("select count(*) from users", Integer.class)).isZero();
	}

	@Test
	void rejectsDuplicateEmailRegardlessOfCase() throws Exception {
		assertThat(post("/api/auth/register", registration("alice@example.com")).statusCode()).isEqualTo(201);

		HttpResponse<String> duplicate = post("/api/auth/register", registration(" ALICE@example.com"));

		assertThat(duplicate.statusCode()).isEqualTo(409);
		assertThat(json(duplicate).path("code").asString()).isEqualTo("EMAIL_ALREADY_REGISTERED");
		assertThat(duplicate.body()).doesNotContain("uq_users_email");
		assertThat(jdbcTemplate.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
	}

	static List<Arguments> invalidRegistrations() {
		return List.of(
				Arguments.of("email", "not-an-email", VALID_PASSWORD, "Alice"),
				Arguments.of("email", "alice@localhost", VALID_PASSWORD, "Alice"),
				Arguments.of("email", "ü@example.com", VALID_PASSWORD, "Alice"),
				Arguments.of("email", "a b@example.com", VALID_PASSWORD, "Alice"),
				Arguments.of("email", "a".repeat(243) + "@example.com", VALID_PASSWORD, "Alice"),
				Arguments.of("email", "   ", VALID_PASSWORD, "Alice"),
				Arguments.of("password", "alice@example.com", "fourteen chars", "Alice"),
				Arguments.of("password", "alice@example.com", "a".repeat(73), "Alice"),
				Arguments.of("password", "alice@example.com", "é".repeat(37), "Alice"),
				Arguments.of("displayName", "alice@example.com", VALID_PASSWORD, "   "),
				Arguments.of("displayName", "alice@example.com", VALID_PASSWORD, "a".repeat(101)));
	}

	@ParameterizedTest(name = "[{index}] invalid {0}")
	@MethodSource("invalidRegistrations")
	void rejectsInvalidRegistration(String field, String email, String password, String displayName)
			throws Exception {
		HttpResponse<String> response = post("/api/auth/register",
				Map.of("email", email, "password", password, "displayName", displayName));

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode error = json(response);
		assertThat(error.path("code").asString()).isEqualTo("VALIDATION_FAILED");
		assertThat(error.path("message").asString()).startsWith("Field '" + field + "'");
	}

	static List<String> boundaryPasswords() {
		// 15 characters including spaces; 36 two-byte characters = exactly 72 UTF-8 bytes.
		return List.of("a b c d e f g h", "é".repeat(36));
	}

	@ParameterizedTest
	@MethodSource("boundaryPasswords")
	void acceptsPasswordsAtPolicyBoundaries(String password) throws Exception {
		HttpResponse<String> response = post("/api/auth/register",
				Map.of("email", "boundary@example.com", "password", password, "displayName", "Boundary"));

		assertThat(response.statusCode()).isEqualTo(201);
	}

	@Test
	void rejectsMalformedJsonWithoutParserDetails() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/auth/register"))
				.POST(HttpRequest.BodyPublishers.ofString("{\"email\": ")).header("Content-Type", "application/json"));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(json(response).path("message").asString()).isEqualTo("Request body is missing or malformed.");
		assertThat(response.body()).doesNotContain("jackson").doesNotContain("line:");
	}

	// ---- Login ----

	@Test
	void loginIssuesMinimalHs256TokenWithoutPii() throws Exception {
		String userId = registerUser("alice@example.com", VALID_PASSWORD);

		HttpResponse<String> response = post("/api/auth/login",
				Map.of("email", " ALICE@example.com ", "password", VALID_PASSWORD));

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json(response);
		assertThat(body.path("tokenType").asString()).isEqualTo("Bearer");
		assertThat(body.path("expiresIn").asLong()).isEqualTo(3600);
		String token = body.path("accessToken").asString();

		Jwt jwt = jwtDecoder.decode(token);
		assertThat(jwt.getClaims()).containsOnlyKeys("iss", "sub", "role", "iat", "exp");
		assertThat(jwt.getSubject()).isEqualTo(userId);
		assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("crowdpass-api");
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(60));
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
		String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
		assertThat(payload).doesNotContain("alice").doesNotContain("Alice");
	}

	@Test
	void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
		registerUser("alice@example.com", VALID_PASSWORD);

		HttpResponse<String> wrongPassword = post("/api/auth/login",
				Map.of("email", "alice@example.com", "password", "wrong password entirely"));
		HttpResponse<String> unknownEmail = post("/api/auth/login",
				Map.of("email", "nobody@example.com", "password", VALID_PASSWORD));

		for (HttpResponse<String> response : List.of(wrongPassword, unknownEmail)) {
			assertThat(response.statusCode()).isEqualTo(401);
			assertThat(json(response).path("code").asString()).isEqualTo("INVALID_CREDENTIALS");
			assertThat(json(response).path("message").asString()).isEqualTo("Invalid email or password.");
		}
		assertThat(withoutVolatileFields(jsonMap(wrongPassword))).isEqualTo(withoutVolatileFields(jsonMap(unknownEmail)));
	}

	@Test
	void rejectsCorrectPasswordWithExtraCharactersBeyondBcryptLimit() throws Exception {
		String password = "p".repeat(72);
		registerUser("long@example.com", password);

		assertThat(post("/api/auth/login", Map.of("email", "long@example.com", "password", password)).statusCode())
				.isEqualTo(200);
		assertThat(post("/api/auth/login", Map.of("email", "long@example.com", "password", password + "x"))
				.statusCode()).isEqualTo(401);
	}

	// ---- /api/users/me ----

	@Test
	void currentUserReturnsProfileForValidToken() throws Exception {
		String userId = registerUser("alice@example.com", VALID_PASSWORD);
		String token = json(post("/api/auth/login", Map.of("email", "alice@example.com", "password", VALID_PASSWORD)))
				.path("accessToken").asString();

		HttpResponse<String> response = get("/api/users/me", token);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(jsonMap(response)).containsOnlyKeys("id", "email", "displayName", "role", "createdAt")
				.containsEntry("id", userId)
				.containsEntry("email", "alice@example.com")
				.containsEntry("role", "USER");
	}

	@Test
	void validTokenForDeletedOrUnknownUserIsUnauthenticated() throws Exception {
		HttpResponse<String> response = get("/api/users/me",
				jwtTokenService.issueAccessToken(UUID.randomUUID(), Role.USER));

		assertUnauthenticated(response);
	}

	// ---- Token rejection ----

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "missing header", "basic scheme", "bearer without token", "not a jwt",
			"tampered payload", "different secret", "alg none", "wrong issuer", "expired", "missing exp",
			"non-uuid subject", "unknown role" })
	void rejectsInvalidAuthenticationWithStandardError(String scenario) throws Exception {
		UUID userId = insertUser("victim@example.com", Role.USER);
		String header = authorizationHeader(scenario, userId);

		HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/users/me")).GET();
		if (header != null) {
			request.header("Authorization", header);
		}
		assertUnauthenticated(send(request));
	}

	// ---- Role authorization and ownership ----

	@Test
	void organizerEndpointRequiresAuthentication() throws Exception {
		assertUnauthenticated(get("/api/organizer/events", null));
	}

	@Test
	void userIsForbiddenFromOrganizerEndpoint() throws Exception {
		UUID userId = insertUser("user@example.com", Role.USER);

		HttpResponse<String> response = get("/api/organizer/events", jwtTokenService.issueAccessToken(userId, Role.USER));

		assertThat(response.statusCode()).isEqualTo(403);
		Map<String, Object> body = jsonMap(response);
		assertThat(body).containsOnlyKeys("timestamp", "status", "error", "code", "message", "path", "requestId");
		assertThat(body).containsEntry("code", "FORBIDDEN").containsEntry("path", "/api/organizer/events");
		assertThat(body.get("requestId")).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
		assertThat(response.body()).doesNotContain("org.springframework");
	}

	@Test
	void organizerSeesOnlyOwnEventsInEveryStatus() throws Exception {
		UUID organizer = insertUser("org-a@example.com", Role.ORGANIZER);
		UUID otherOrganizer = insertUser("org-b@example.com", Role.ORGANIZER);
		UUID draft = insertEvent(organizer, "DRAFT", 1);
		UUID published = insertEvent(organizer, "PUBLISHED", 2);
		UUID cancelled = insertEvent(organizer, "CANCELLED", 3);
		insertEvent(otherOrganizer, "PUBLISHED", 1);

		HttpResponse<String> response = get("/api/organizer/events",
				jwtTokenService.issueAccessToken(organizer, Role.ORGANIZER));

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode page = json(response);
		assertThat(ids(page)).containsExactly(draft.toString(), published.toString(), cancelled.toString());
		assertThat(page.path("items").get(0).path("status").asString()).isEqualTo("DRAFT");
		assertThat(page.path("totalElements").asLong()).isEqualTo(3);
	}

	@Test
	void adminInheritsOrganizerCapabilityButNotOtherOrganizersEvents() throws Exception {
		UUID admin = insertUser("admin@example.com", Role.ADMIN);
		insertEvent(insertUser("org@example.com", Role.ORGANIZER), "PUBLISHED", 1);

		HttpResponse<String> response = get("/api/organizer/events", jwtTokenService.issueAccessToken(admin, Role.ADMIN));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(ids(json(response))).isEmpty();
	}

	@Test
	void organizerAndAdminRetainUserCapabilities() throws Exception {
		for (Role role : List.of(Role.ORGANIZER, Role.ADMIN)) {
			UUID id = insertUser(role.name().toLowerCase() + "@example.com", role);

			assertThat(get("/api/users/me", jwtTokenService.issueAccessToken(id, role)).statusCode()).isEqualTo(200);
		}
	}

	@Test
	void authorizationUsesTokenRoleUntilTokenExpires() throws Exception {
		UUID promoted = insertUser("promoted@example.com", Role.ORGANIZER);

		HttpResponse<String> response = get("/api/organizer/events",
				jwtTokenService.issueAccessToken(promoted, Role.USER));

		assertThat(response.statusCode()).isEqualTo(403);
	}

	@Test
	void organizerEndpointValidatesPagination() throws Exception {
		UUID organizer = insertUser("org@example.com", Role.ORGANIZER);

		HttpResponse<String> response = get("/api/organizer/events?size=101",
				jwtTokenService.issueAccessToken(organizer, Role.ORGANIZER));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(json(response).path("code").asString()).isEqualTo("VALIDATION_FAILED");
	}

	// ---- helpers ----

	private String authorizationHeader(String scenario, UUID userId) {
		Instant now = Instant.now();
		return switch (scenario) {
			case "missing header" -> null;
			case "basic scheme" -> "Basic " + Base64.getEncoder().encodeToString("victim:password".getBytes());
			case "bearer without token" -> "Bearer";
			case "not a jwt" -> "Bearer not.a.jwt";
			case "tampered payload" -> "Bearer " + tamperRole(jwtTokenService.issueAccessToken(userId, Role.USER));
			case "different secret" -> "Bearer " + encode(foreignEncoder(), claims(userId, "crowdpass-api", now,
					now.plus(1, ChronoUnit.HOURS), "USER"));
			case "alg none" -> "Bearer " + unsignedToken(userId);
			case "wrong issuer" -> "Bearer " + encode(jwtEncoder, claims(userId, "someone-else", now,
					now.plus(1, ChronoUnit.HOURS), "USER"));
			case "expired" -> "Bearer " + encode(jwtEncoder, claims(userId, "crowdpass-api",
					now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), "USER"));
			case "missing exp" -> "Bearer " + encode(jwtEncoder, JwtClaimsSet.builder().issuer("crowdpass-api")
					.subject(userId.toString()).claim("role", "USER").issuedAt(now).build());
			case "non-uuid subject" -> "Bearer " + encode(jwtEncoder, JwtClaimsSet.builder().issuer("crowdpass-api")
					.subject("admin").claim("role", "USER").issuedAt(now).expiresAt(now.plus(1, ChronoUnit.HOURS))
					.build());
			case "unknown role" -> "Bearer " + encode(jwtEncoder, claims(userId, "crowdpass-api", now,
					now.plus(1, ChronoUnit.HOURS), "SUPERUSER"));
			default -> throw new IllegalArgumentException(scenario);
		};
	}

	private static JwtClaimsSet claims(UUID userId, String issuer, Instant issuedAt, Instant expiresAt, String role) {
		return JwtClaimsSet.builder().issuer(issuer).subject(userId.toString()).claim("role", role)
				.issuedAt(issuedAt).expiresAt(expiresAt).build();
	}

	private static String encode(JwtEncoder encoder, JwtClaimsSet claims) {
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	private static JwtEncoder foreignEncoder() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return NimbusJwtEncoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).algorithm(MacAlgorithm.HS256)
				.build();
	}

	private static String tamperRole(String token) {
		String[] parts = token.split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
				.replace("\"USER\"", "\"ADMIN\"");
		return parts[0] + "." + base64Url(payload) + "." + parts[2];
	}

	private static String unsignedToken(UUID userId) {
		long now = Instant.now().getEpochSecond();
		String payload = "{\"iss\":\"crowdpass-api\",\"sub\":\"" + userId + "\",\"role\":\"ADMIN\",\"iat\":" + now
				+ ",\"exp\":" + (now + 3600) + "}";
		return base64Url("{\"alg\":\"none\"}") + "." + base64Url(payload) + ".";
	}

	private static String base64Url(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private void assertUnauthenticated(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).isEqualTo(401);
		Map<String, Object> body = jsonMap(response);
		assertThat(body).containsOnlyKeys("timestamp", "status", "error", "code", "message", "path", "requestId");
		assertThat(body).containsEntry("code", "UNAUTHENTICATED")
				.containsEntry("message", "Authentication is required.")
				.containsEntry("error", "Unauthorized");
		assertThat(body.get("requestId")).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
		assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
		assertThat(response.body()).doesNotContainIgnoringCase("jwt")
				.doesNotContainIgnoringCase("expired")
				.doesNotContainIgnoringCase("signature")
				.doesNotContain("org.springframework");
	}

	private String registerUser(String email, String password) throws Exception {
		HttpResponse<String> response = post("/api/auth/register",
				Map.of("email", email, "password", password, "displayName", "Test User"));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return json(response).path("id").asString();
	}

	private static Map<String, Object> registration(String email) {
		Map<String, Object> body = new HashMap<>();
		body.put("email", email);
		body.put("password", VALID_PASSWORD);
		body.put("displayName", "Test User");
		return body;
	}

	private UUID insertUser(String email, Role role) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				insert into users (id, email, password_hash, display_name, role, created_at, updated_at)
				values (?, ?, 'unused', 'Test User', ?, ?, ?)
				""", id, email, role.name(), CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.atOffset(ZoneOffset.UTC));
		return id;
	}

	private UUID insertEvent(UUID organizerId, String status, int startsInDays) {
		UUID id = UUID.randomUUID();
		Instant starts = Instant.parse("2031-01-01T00:00:00Z").plus(startsInDays, ChronoUnit.DAYS);
		jdbcTemplate.update("""
				insert into events (id, organizer_id, name, capacity, status, time_zone, registration_open_at,
				    registration_close_at, starts_at, ends_at, cancelled_at, created_at, updated_at)
				values (?, ?, 'Event', 50, ?, 'UTC', ?, ?, ?, ?, ?, ?, ?)
				""", id, organizerId, status, starts.minus(10, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
				starts.atOffset(ZoneOffset.UTC), starts.atOffset(ZoneOffset.UTC),
				starts.plus(2, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC),
				status.equals("CANCELLED") ? CREATED_AT.atOffset(ZoneOffset.UTC) : null,
				CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.atOffset(ZoneOffset.UTC));
		return id;
	}

	private static List<String> ids(JsonNode page) {
		List<String> ids = new java.util.ArrayList<>();
		for (JsonNode item : page.path("items")) {
			ids.add(item.path("id").asString());
		}
		return ids;
	}

	private static Map<String, Object> withoutVolatileFields(Map<String, Object> body) {
		Map<String, Object> copy = new HashMap<>(body);
		copy.remove("timestamp");
		copy.remove("requestId");
		return copy;
	}

	private HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
		return send(HttpRequest.newBuilder(uri(path))
				.POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
				.header("Content-Type", "application/json"));
	}

	private HttpResponse<String> get(String path, String bearerToken) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
		if (bearerToken != null) {
			request.header("Authorization", "Bearer " + bearerToken);
		}
		return send(request);
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private JsonNode json(HttpResponse<String> response) {
		return jsonMapper.readTree(response.body());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> jsonMap(HttpResponse<String> response) {
		return jsonMapper.readValue(response.body(), Map.class);
	}

}
