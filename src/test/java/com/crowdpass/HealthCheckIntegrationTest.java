package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

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
	void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
		assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
		assertThat(get("/actuator/beans").statusCode()).isEqualTo(404);
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
