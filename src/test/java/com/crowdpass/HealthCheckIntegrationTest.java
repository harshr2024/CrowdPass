package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "management.endpoint.health.show-details=always")
class HealthCheckIntegrationTest {

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtTokenService jwtTokenService;

	@Test
	void healthIsUpAndReportsPostgresConnection() throws Exception {
		HttpResponse<String> response = get("/actuator/health");

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = jsonMapper.readTree(response.body());
		assertThat(body.path("status").asString()).isEqualTo("UP");
		assertThat(body.path("components").path("db").path("status").asString()).isEqualTo("UP");
		assertThat(body.path("components").path("db").path("details").path("database").asString())
				.isEqualTo("PostgreSQL");
	}

	@Test
	void flywayAppliedBaselineMigration() {
		Integer appliedBaseline = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where version = '1' and success",
				Integer.class);

		assertThat(appliedBaseline).isEqualTo(1);
	}

	@Test
	void sensitiveActuatorEndpointsRequireAuthenticationAndAreNotExposed() throws Exception {
		String adminToken = jwtTokenService.issueAccessToken(UUID.randomUUID(), Role.ADMIN);

		assertThat(get("/actuator/env", null).statusCode()).isEqualTo(401);
		assertThat(get("/actuator/env", adminToken).statusCode()).isEqualTo(404);
		assertThat(get("/actuator/beans", adminToken).statusCode()).isEqualTo(404);
	}

	private HttpResponse<String> get(String path) throws Exception {
		return get(path, null);
	}

	private HttpResponse<String> get(String path, String bearerToken) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
		if (bearerToken != null) {
			request.header("Authorization", "Bearer " + bearerToken);
		}
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

}
