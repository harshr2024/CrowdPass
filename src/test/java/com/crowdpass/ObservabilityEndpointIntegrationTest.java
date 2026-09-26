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
import org.springframework.test.context.ActiveProfiles;

import com.crowdpass.auth.JwtTokenService;
import com.crowdpass.user.Role;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("observability")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityEndpointIntegrationTest {

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JwtTokenService tokens;

	@Test
	void operationalMetricsRequireAdminEvenWhenExplicitlyExposed() throws Exception {
		String user = tokens.issueAccessToken(UUID.randomUUID(), Role.USER);
		String admin = tokens.issueAccessToken(UUID.randomUUID(), Role.ADMIN);

		assertThat(get("/actuator/prometheus", null).statusCode()).isEqualTo(401);
		assertThat(get("/actuator/prometheus", user).statusCode()).isEqualTo(403);
		HttpResponse<String> response = get("/actuator/prometheus", admin);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("jvm_memory_used_bytes");
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
		if (token != null) request.header("Authorization", "Bearer " + token);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
