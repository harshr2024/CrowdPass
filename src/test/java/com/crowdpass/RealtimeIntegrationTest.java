package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.crowdpass.auth.JwtTokenService;
import com.crowdpass.notification.NotificationService;
import com.crowdpass.realtime.RealtimeConnectionRegistry;
import com.crowdpass.reservation.WaitlistPromotedEvent;
import com.crowdpass.user.Role;

import javax.sql.DataSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
		"crowdpass.realtime.heartbeat=100ms", "crowdpass.realtime.max-stream-lifetime=3s" })
class RealtimeIntegrationTest {

	private static final Instant NOW = MutableClock.Config.START;
	private final HttpClient http = HttpClient.newHttpClient();
	private final List<SseStream> streams = new ArrayList<>();

	@LocalServerPort private int port;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;
	@Autowired private JwtTokenService tokens;
	@Autowired private NotificationService notifications;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private RealtimeConnectionRegistry registry;
	@Autowired private JsonMapper json;
	@Autowired private PostgreSQLContainer postgresContainer;
	@Autowired private GenericContainer<?> redisContainer;

	@MockitoSpyBean
	private StringRedisTemplate redis;

	private ReservationTestData data;

	@BeforeEach
	void setUp() {
		data = new ReservationTestData(jdbc);
		data.reset();
	}

	@AfterEach
	void closeStreams() throws Exception {
		streams.forEach(SseStream::close);
		streams.clear();
		reset(redis);
		await(() -> registry.activeConnectionCount() == 0);
	}

	@Test
	void streamRequiresBearerAndEveryConnectionStartsWithSyncThenHeartbeats() throws Exception {
		HttpResponse<String> unauthenticated = http.send(HttpRequest.newBuilder(uri(port, "/api/notifications/stream"))
				.GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(unauthenticated.statusCode()).isEqualTo(401);
		assertThat(json.readTree(unauthenticated.body()).path("code").asString()).isEqualTo("UNAUTHENTICATED");

		UUID user = data.insertUser("USER");
		SseStream stream = open(port, tokens.issueAccessToken(user, Role.USER), null);
		assertThat(stream.nextEvent("event:notifications.sync", Duration.ofSeconds(2)))
				.contains("data:{\"version\":1,\"resource\":\"notifications\"}");
		assertThat(stream.nextLine(line -> line.equals(":keepalive"), Duration.ofSeconds(2))).isEqualTo(":keepalive");
	}

	@Test
	void signalIsAfterCommitOwnerScopedAndDuplicateInvalidationsAreHarmless() throws Exception {
		Fixture fixture = fixture();
		UUID other = data.insertUser("USER");
		SseStream owner = open(port, tokens.issueAccessToken(fixture.userId, Role.USER), null);
		SseStream stranger = open(port, tokens.issueAccessToken(other, Role.USER), null);
		owner.nextEvent("event:notifications.sync", Duration.ofSeconds(2));
		stranger.nextEvent("event:notifications.sync", Duration.ofSeconds(2));

		TransactionTemplate tx = new TransactionTemplate(transactions);
		tx.executeWithoutResult(status -> {
			notifications.recordWaitlistPromoted(fixture.sourceEventId, NOW, fixture.event());
			assertThat(owner.hasEventUnchecked("event:notifications.changed", Duration.ofMillis(250))).isFalse();
		});
		String changed = owner.nextEvent("event:notifications.changed", Duration.ofSeconds(3));
		UUID notificationId = UUID.fromString(value(changed, "id:"));
		assertThat(stranger.hasEvent("event:notifications.changed", Duration.ofMillis(350))).isFalse();

		redis.convertAndSend("crowdpass:realtime:v1:notifications",
				"{\"version\":1,\"kind\":\"NOTIFICATION_CREATED\",\"userId\":\"" + fixture.userId
						+ "\",\"notificationId\":\"" + notificationId + "\"}");
		assertThat(owner.nextEvent("event:notifications.changed", Duration.ofSeconds(2)))
				.contains(notificationId.toString());
		assertThat(notificationItems(port, fixture.userId)).hasSize(1);
	}

	@Test
	void rollbackEmitsNothingAndCreatesNoDurableNotification() throws Exception {
		Fixture fixture = fixture();
		SseStream stream = open(port, tokens.issueAccessToken(fixture.userId, Role.USER), null);
		stream.nextEvent("event:notifications.sync", Duration.ofSeconds(2));
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			notifications.recordWaitlistPromoted(fixture.sourceEventId, NOW, fixture.event());
			status.setRollbackOnly();
		});

		assertThat(stream.hasEvent("event:notifications.changed", Duration.ofMillis(350))).isFalse();
		assertThat(notificationItems(port, fixture.userId)).isEmpty();
	}

	@Test
	void redisPublishFailureCannotUndoNotificationOrPreventHttpRecovery() throws Exception {
		Fixture fixture = fixture();
		doThrow(new RedisConnectionFailureException("simulated outage"))
				.when(redis).convertAndSend(anyString(), anyString());

		assertThat(notifications.recordWaitlistPromoted(fixture.sourceEventId, NOW, fixture.event())).isTrue();

		assertThat(notificationItems(port, fixture.userId)).hasSize(1);
		assertThat(jdbc.queryForObject("select count(*) from notifications", Long.class)).isEqualTo(1);
	}

	@Test
	void realtimePublicationCanObserveTheCommittedNotificationFromAnIndependentConnection() throws Exception {
		Fixture fixture = fixture();
		AtomicBoolean durableAtPublish = new AtomicBoolean();
		doAnswer(invocation -> {
			try (var connection = dataSource.getConnection();
					var statement = connection.prepareStatement(
							"select count(*) from notifications where source_event_id = ?")) {
				statement.setObject(1, fixture.sourceEventId);
				try (var result = statement.executeQuery()) {
					result.next();
					durableAtPublish.set(result.getLong(1) == 1);
				}
			}
			return invocation.callRealMethod();
		}).when(redis).convertAndSend(anyString(), anyString());

		notifications.recordWaitlistPromoted(fixture.sourceEventId, NOW, fixture.event());

		assertThat(durableAtPublish).isTrue();
	}

	@Test
	void missedSignalRecoversWithSyncAndHttpRefetch() throws Exception {
		Fixture fixture = fixture();
		notifications.recordWaitlistPromoted(fixture.sourceEventId, NOW, fixture.event());

		SseStream reconnected = open(port, tokens.issueAccessToken(fixture.userId, Role.USER),
				UUID.randomUUID().toString());

		assertThat(reconnected.nextEvent("event:notifications.sync", Duration.ofSeconds(2))).isNotBlank();
		assertThat(notificationItems(port, fixture.userId)).hasSize(1);
	}

	@Test
	void fourthConnectionForOneUserGetsApiErrorBeforeStreaming() throws Exception {
		UUID user = data.insertUser("USER");
		String token = tokens.issueAccessToken(user, Role.USER);
		for (int i = 0; i < 3; i++) open(port, token, null);

		HttpResponse<String> rejected = http.send(HttpRequest.newBuilder(uri(port, "/api/notifications/stream"))
				.header("Authorization", "Bearer " + token).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(rejected.statusCode()).isEqualTo(429);
		assertThat(json.readTree(rejected.body()).path("code").asString()).isEqualTo("REALTIME_CONNECTION_LIMIT");
	}

	@Test
	void independentlyStartedInstanceReceivesSignalFromThisInstanceThroughRealRedis() throws Exception {
		Fixture fixture = fixture();
		ConfigurableApplicationContext second = new SpringApplication(CrowdPassApplication.class).run(
				"--server.port=0",
				"--spring.datasource.url=" + postgresContainer.getJdbcUrl(),
				"--spring.datasource.username=" + postgresContainer.getUsername(),
				"--spring.datasource.password=" + postgresContainer.getPassword(),
				"--spring.data.redis.host=" + redisContainer.getHost(),
				"--spring.data.redis.port=" + redisContainer.getMappedPort(6379),
				"--crowdpass.jwt.ephemeral-secret-allowed=true",
				"--crowdpass.rate-limit.ephemeral-secret-allowed=true",
				"--crowdpass.realtime.heartbeat=100ms",
				"--crowdpass.realtime.max-stream-lifetime=3s",
				"--crowdpass.messaging.sqs.endpoint=http://127.0.0.1:9",
				"--crowdpass.messaging.sqs.access-key-id=test",
				"--crowdpass.messaging.sqs.secret-access-key=test");
		try {
			int secondPort = ((WebServerApplicationContext) second).getWebServer().getPort();
			String secondToken = second.getBean(JwtTokenService.class).issueAccessToken(fixture.userId, Role.USER);
			SseStream onSecond = open(secondPort, secondToken, null);
			onSecond.nextEvent("event:notifications.sync", Duration.ofSeconds(2));

			notifications.recordWaitlistPromoted(fixture.sourceEventId, NOW, fixture.event());

			assertThat(onSecond.nextEvent("event:notifications.changed", Duration.ofSeconds(3))).isNotBlank();
		}
		finally {
			second.close();
		}
	}

	private Fixture fixture() {
		UUID organizer = data.insertUser("ORGANIZER");
		UUID user = data.insertUser("USER");
		UUID event = data.insertEvent(organizer, "PUBLISHED", 10, NOW.minusSeconds(60), NOW.plusSeconds(3600));
		UUID reservation = UUID.randomUUID();
		jdbc.update("insert into reservations (id,event_id,user_id,status,created_at) values (?,?,?,'CONFIRMED',?)",
				reservation, event, user, NOW.atOffset(ZoneOffset.UTC));
		jdbc.update("update events set reserved_count=1 where id=?", event);
		return new Fixture(user, event, reservation, UUID.randomUUID(), UUID.randomUUID());
	}

	private JsonNode notificationItems(int serverPort, UUID user) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(serverPort, "/api/notifications"))
				.header("Authorization", "Bearer " + tokenFor(serverPort, user)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json.readTree(response.body()).path("items");
	}

	private String tokenFor(int serverPort, UUID user) {
		if (serverPort != port) throw new IllegalArgumentException("only the primary instance is used for HTTP recovery");
		return tokens.issueAccessToken(user, Role.USER);
	}

	private SseStream open(int serverPort, String token, String lastEventId) throws Exception {
		HttpResponse<InputStream> response = http.send(streamRequest(serverPort, token, lastEventId).build(),
				HttpResponse.BodyHandlers.ofInputStream());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("content-type").orElse(""))
				.startsWith("text/event-stream");
		SseStream stream = new SseStream(response.body());
		streams.add(stream);
		return stream;
	}

	private static HttpRequest.Builder streamRequest(int port, String token, String lastEventId) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(port, "/api/notifications/stream"))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "text/event-stream")
				.GET();
		if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
		return request;
	}

	private static URI uri(int port, String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private static String value(String event, String prefix) {
		return event.lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow().substring(prefix.length());
	}

	private static void await(Check check) throws Exception {
		for (int i = 0; i < 100 && !check.done(); i++) Thread.sleep(10);
		assertThat(check.done()).isTrue();
	}

	@FunctionalInterface private interface Check { boolean done() throws Exception; }

	private record Fixture(UUID userId, UUID eventId, UUID reservationId, UUID waitlistEntryId,
			UUID sourceEventId) {
		WaitlistPromotedEvent event() {
			return new WaitlistPromotedEvent(userId, eventId, reservationId, waitlistEntryId);
		}
	}

	private static final class SseStream implements AutoCloseable {
		private static final String EOF = "<EOF>";
		private final InputStream input;
		private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

		private SseStream(InputStream input) {
			this.input = input;
			Thread.ofVirtual().name("sse-test-reader").start(() -> {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) lines.add(line);
				}
				catch (IOException ignored) {
				}
				finally {
					lines.add(EOF);
				}
			});
		}

		private String nextEvent(String expectedLine, Duration timeout) throws InterruptedException {
			StringBuilder event = new StringBuilder();
			long deadline = System.nanoTime() + timeout.toNanos();
			while (System.nanoTime() < deadline) {
				String line = lines.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				if (line == null || EOF.equals(line)) break;
				if (line.isEmpty()) {
					if (event.toString().contains(expectedLine)) return event.toString();
					event.setLength(0);
				}
				else {
					event.append(line).append('\n');
				}
			}
			throw new AssertionError("Did not receive " + expectedLine + "; last event was " + event);
		}

		private boolean hasEvent(String expectedLine, Duration timeout) throws InterruptedException {
			try {
				nextEvent(expectedLine, timeout);
				return true;
			}
			catch (AssertionError absent) {
				return false;
			}
		}

		private boolean hasEventUnchecked(String expectedLine, Duration timeout) {
			try {
				return hasEvent(expectedLine, timeout);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}

		private String nextLine(java.util.function.Predicate<String> predicate, Duration timeout)
				throws InterruptedException {
			long deadline = System.nanoTime() + timeout.toNanos();
			while (System.nanoTime() < deadline) {
				String line = lines.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				if (line == null || EOF.equals(line)) break;
				if (predicate.test(line)) return line;
			}
			throw new AssertionError("Expected SSE line was not received");
		}

		@Override public void close() {
			try { input.close(); } catch (IOException ignored) { }
		}
	}
}
