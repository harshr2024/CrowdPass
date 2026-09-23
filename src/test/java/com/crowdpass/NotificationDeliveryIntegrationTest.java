package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.crowdpass.auth.JwtTokenService;
import com.crowdpass.notification.NotificationConsumer;
import com.crowdpass.notification.NotificationResponse;
import com.crowdpass.notification.NotificationService;
import com.crowdpass.outbox.OutboxPublisher;
import com.crowdpass.reservation.ReservationResponse;
import com.crowdpass.reservation.ReservationService;
import com.crowdpass.reservation.WaitlistPromotedEvent;
import com.crowdpass.reservation.WaitlistService;
import com.crowdpass.user.Role;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publisher, consumer, dead-letter, and notification API behavior against PostgreSQL and ElasticMQ.
 * The background publisher and consumer are off; tests call {@code publishOnce} and {@code pollOnce}.
 * ElasticMQ visibility is 1 second here so redelivery does not wait the 30 seconds configured for Compose.
 */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class NotificationDeliveryIntegrationTest {

	private static final Instant NOW = MutableClock.Config.START;
	private static final String CANARY = "canary-secret-value";
	private static final String DLQ_NAME = "crowdpass-notifications-dlq";

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@DynamicPropertySource
	static void elasticMq(DynamicPropertyRegistry registry) {
		registry.add("crowdpass.messaging.sqs.endpoint", ElasticMqContainer::endpoint);
	}

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private MutableClock clock;

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private WaitlistService waitlistService;

	@Autowired
	private OutboxPublisher publisher;

	@Autowired
	private NotificationConsumer consumer;

	@Autowired
	private JwtTokenService jwtTokenService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private com.crowdpass.config.NotificationQueue notificationQueue;

	@MockitoSpyBean
	private SqsClient sqsClient;

	@MockitoSpyBean
	private NotificationService notificationService;

	private ReservationTestData data;
	private String mainQueueUrl;
	private String dlqUrl;

	@BeforeEach
	void setUp() {
		data = new ReservationTestData(jdbcTemplate);
		data.reset();
		clock.set(NOW);
		mainQueueUrl = notificationQueue.url();
		dlqUrl = sqsClient.getQueueUrl(request -> request.queueName(DLQ_NAME)).queueUrl();
		sqsClient.purgeQueue(request -> request.queueUrl(mainQueueUrl));
		sqsClient.purgeQueue(request -> request.queueUrl(dlqUrl));
	}

	@AfterEach
	void resetSpies() {
		reset(sqsClient, notificationService);
	}

	@Test
	void publisherSendsAndMarksOnlyAcknowledgedEvents() {
		UUID outboxId = promote();

		assertThat(publisher.publishOnce()).isEqualTo(1);

		assertThat(pendingCount()).isZero();
		assertThat(publishedCount()).isEqualTo(1);
		List<Message> messages = drain(mainQueueUrl);
		assertThat(messages).hasSize(1);
		JsonNode body = jsonMapper.readTree(messages.getFirst().body());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "type", "version", "occurredAt", "data");
		assertThat(body.path("id").asString()).isEqualTo(outboxId.toString());
		assertThat(body.path("type").asString()).isEqualTo("WAITLIST_PROMOTED");
		assertThat(body.path("version").asInt()).isEqualTo(1);
		assertThat(body.path("data").propertyNames()).containsExactlyInAnyOrder("userId", "eventId", "reservationId",
				"waitlistEntryId");
		assertThat(body.toString()).doesNotContain("email", "token", "password");
	}

	/**
	 * The HTTP call succeeds. The response then reports one entry accepted and one rejected, which is
	 * the SDK's per-entry result, not a failed call. Only the rejected row is sent on the next attempt.
	 */
	@Test
	void partialBatchMarksOnlyTheEntriesSqsAccepted() {
		UUID first = promote();
		UUID second = promote();
		List<List<String>> sentIds = new ArrayList<>();
		AtomicInteger calls = new AtomicInteger();
		doAnswer(invocation -> {
			List<String> ids = entryIds(invocation.getArgument(0));
			sentIds.add(ids);
			if (calls.getAndIncrement() == 0) {
				SendMessageBatchResponse real = (SendMessageBatchResponse) invocation.callRealMethod();
				assertThat(real.sdkHttpResponse().isSuccessful()).isTrue();
				assertThat(real.failed()).isEmpty();
				assertThat(real.successful()).extracting(SendMessageBatchResultEntry::id)
						.containsExactlyInAnyOrderElementsOf(ids);
				String kept = ids.getFirst();
				String rejected = ids.get(1);
				return SendMessageBatchResponse.builder()
						.successful(real.successful().stream().filter(entry -> entry.id().equals(kept)).toList())
						.failed(BatchResultErrorEntry.builder()
								.id(rejected)
								.code("InternalError")
								.senderFault(false)
								.message("simulated " + CANARY)
								.build())
						.build();
			}
			return invocation.callRealMethod();
		}).when(sqsClient).sendMessageBatch(any(Consumer.class));

		assertThat(publisher.publishOnce()).isEqualTo(2);
		assertThat(sentIds.getFirst()).containsExactlyInAnyOrder(first.toString(), second.toString());

		UUID publishedId = jdbcTemplate.queryForObject(
				"select id from outbox_events where published_at is not null", UUID.class);
		UUID failedId = jdbcTemplate.queryForObject(
				"select id from outbox_events where published_at is null", UUID.class);
		assertThat(List.of(publishedId, failedId)).containsExactlyInAnyOrder(first, second);
		Instant publishedAt = instant("select published_at from outbox_events where id = ?", publishedId);
		assertThat(jdbcTemplate.queryForObject("select attempts from outbox_events where id = ?", Integer.class,
				publishedId)).isZero();
		assertThat(jdbcTemplate.queryForObject("select last_error is null from outbox_events where id = ?",
				Boolean.class, publishedId)).isTrue();

		assertThat(jdbcTemplate.queryForObject("select attempts from outbox_events where id = ?", Integer.class,
				failedId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("select last_error from outbox_events where id = ?", String.class,
				failedId)).isEqualTo("InternalError");
		assertThat(instant("select next_attempt_at from outbox_events where id = ?", failedId))
				.isEqualTo(NOW.plusSeconds(2));

		clock.advance(Duration.ofSeconds(2));
		assertThat(publisher.publishOnce()).isEqualTo(1);

		assertThat(sentIds).hasSize(2);
		assertThat(sentIds.get(1)).containsExactly(failedId.toString());
		assertThat(pendingCount()).isZero();
		assertThat(jdbcTemplate.queryForList("select id from outbox_events where published_at is not null", UUID.class))
				.containsExactlyInAnyOrder(first, second);
		assertThat(instant("select published_at from outbox_events where id = ?", publishedId)).isEqualTo(publishedAt);
		assertThat(jdbcTemplate.queryForObject("select attempts from outbox_events where id = ?", Integer.class,
				publishedId)).isZero();
	}

	@Test
	void lostSendResponseStaysPendingThenRedeliveryCreatesOneNotification() {
		promote();
		AtomicInteger calls = new AtomicInteger();
		doAnswer(invocation -> {
			Object real = invocation.callRealMethod();
			if (calls.getAndIncrement() == 0) {
				throw SdkClientException.create("response lost");
			}
			return real;
		}).when(sqsClient).sendMessageBatch(any(Consumer.class));

		assertThat(publisher.publishOnce()).isEqualTo(1);
		assertThat(publishedCount()).isZero();
		assertThat(pendingCount()).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isEqualTo(1);

		clock.advance(Duration.ofSeconds(2));
		assertThat(publisher.publishOnce()).isEqualTo(1);
		assertThat(publishedCount()).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isEqualTo(2);

		assertThat(consumer.pollOnce()).isEqualTo(2);
		assertThat(notificationCount()).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isZero();
	}

	@Test
	void twoPublishersDrainTwoHundredRowsWithoutLosingOrDuplicatingIds() throws Exception {
		List<UUID> inserted = insertPending(200);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(publisher::publishAllDue);
			Future<?> second = executor.submit(publisher::publishAllDue);
			first.get(60, TimeUnit.SECONDS);
			second.get(60, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(pendingCount()).isZero();
		assertThat(publishedCount()).isEqualTo(200);
		List<UUID> received = new ArrayList<>();
		for (Message message : drain(mainQueueUrl)) {
			received.add(UUID.fromString(jsonMapper.readTree(message.body()).path("id").asString()));
		}
		assertThat(received).hasSize(200).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(inserted);
	}

	@Test
	void publisherSkipsRowsLockedByAnotherTransaction() throws Exception {
		insertPending(30);
		List<UUID> held = jdbcTemplate.queryForList("""
				select id from outbox_events order by next_attempt_at, id limit 10
				""", UUID.class);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TransactionTemplate holderTransaction = new TransactionTemplate(transactionManager);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<?> holder = null;
		Future<Integer> published = null;
		try {
			holder = executor.submit(() -> holderTransaction.executeWithoutResult(status -> {
				for (UUID id : held) {
					jdbcTemplate.queryForObject("select id from outbox_events where id = ? for update", UUID.class, id);
				}
				locked.countDown();
				try {
					if (!release.await(20, TimeUnit.SECONDS)) {
						throw new IllegalStateException("lock was not released");
					}
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
			}));
			assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
			published = executor.submit(publisher::publishOnce);
			assertThat(published.get(5, TimeUnit.SECONDS)).isEqualTo(10);
			List<UUID> publishedIds = jdbcTemplate.queryForList(
					"select id from outbox_events where published_at is not null", UUID.class);
			assertThat(publishedIds).hasSize(10).doesNotContainAnyElementsOf(held);
		}
		finally {
			release.countDown();
			if (holder != null) {
				holder.get(20, TimeUnit.SECONDS);
			}
			if (published != null) {
				published.get(20, TimeUnit.SECONDS);
			}
			executor.shutdownNow();
		}
	}

	@Test
	void consumerStoresOneNotificationAndDeletesTheMessage(CapturedOutput output) {
		Promoted promoted = promoteReturningDetails();
		ObjectNode data = (ObjectNode) jsonMapper.readTree(jdbcTemplate.queryForObject(
				"select data::text from outbox_events where id = ?", String.class, promoted.outboxId()));
		data.put("email", CANARY);
		ObjectNode envelope = jsonMapper.createObjectNode();
		envelope.put("id", promoted.outboxId().toString());
		envelope.put("type", "WAITLIST_PROMOTED");
		envelope.put("version", 1);
		envelope.put("occurredAt", NOW.toString());
		envelope.put("trace", CANARY);
		envelope.set("data", data);
		sqsClient.sendMessage(request -> request.queueUrl(mainQueueUrl).messageBody(jsonMapper.writeValueAsString(envelope)));

		assertThat(consumer.pollOnce()).isEqualTo(1);

		assertThat(notificationCount()).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isZero();
		UUID notificationId = jdbcTemplate.queryForObject("select id from notifications", UUID.class);
		assertThat(notificationId.version()).isEqualTo(7);
		assertThat(jdbcTemplate.queryForObject("select source_event_id from notifications", UUID.class))
				.isEqualTo(promoted.outboxId());
		assertThat(output.getOut() + output.getErr()).doesNotContain(CANARY);
	}

	@Test
	void duplicateDeliveryKeepsASingleNotification() {
		UUID outboxId = promote();
		publisher.publishOnce();
		assertThat(consumer.pollOnce()).isEqualTo(1);
		String body = jsonMapper.writeValueAsString(envelopeFor(outboxId));
		sqsClient.sendMessage(request -> request.queueUrl(mainQueueUrl).messageBody(body));

		assertThat(consumer.pollOnce()).isEqualTo(1);

		assertThat(notificationCount()).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isZero();
	}

	@Test
	void databaseFailureBeforeCommitRedeliversTheMessage() throws Exception {
		promote();
		publisher.publishOnce();
		doThrow(new TransientDataAccessResourceException("database unavailable"))
				.doCallRealMethod()
				.when(notificationService)
				.recordWaitlistPromoted(any(UUID.class), any(Instant.class), any(WaitlistPromotedEvent.class));

		assertThat(consumer.pollOnce()).isEqualTo(1);
		assertThat(notificationCount()).isZero();
		assertThat(messageCount(mainQueueUrl)).isEqualTo(1);

		Thread.sleep(1100);
		assertThat(consumer.pollOnce()).isEqualTo(1);
		assertThat(notificationCount()).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isZero();
	}

	@Test
	void deleteFailureAfterCommitStillResultsInOneNotification() throws Exception {
		promote();
		publisher.publishOnce();
		doThrow(SdkClientException.create("delete failed"))
				.doCallRealMethod()
				.when(sqsClient)
				.deleteMessage(any(Consumer.class));

		assertThat(consumer.pollOnce()).isEqualTo(1);
		assertThat(notificationCount()).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isEqualTo(1);

		Thread.sleep(1100);
		assertThat(consumer.pollOnce()).isEqualTo(1);
		assertThat(notificationCount()).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isZero();
	}

	@Test
	void malformedJsonIsRedrivenToTheDlq(CapturedOutput output) throws Exception {
		sendRaw("not-json " + CANARY);
		redrive();
		assertThat(messageCount(dlqUrl)).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isZero();
		assertThat(notificationCount()).isZero();
		String logs = output.getOut() + output.getErr();
		assertThat(logs).contains("reason=malformed").doesNotContain(CANARY);
	}

	@Test
	void unknownTypeIsRedrivenToTheDlq(CapturedOutput output) throws Exception {
		sendRaw(poisonEnvelope("RESERVATION_CONFIRMED", 1, "{}"));
		redrive();
		assertThat(messageCount(dlqUrl)).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isZero();
		assertThat(notificationCount()).isZero();
		assertThat(output.getOut() + output.getErr()).contains("reason=unsupported_type").doesNotContain(CANARY);
	}

	@Test
	void unsupportedVersionIsRedrivenToTheDlq(CapturedOutput output) throws Exception {
		sendRaw(poisonEnvelope("WAITLIST_PROMOTED", 2, "{\"userId\":\"" + UUID.randomUUID() + "\"}"));
		redrive();
		assertThat(messageCount(dlqUrl)).isEqualTo(1);
		assertThat(messageCount(mainQueueUrl)).isZero();
		assertThat(notificationCount()).isZero();
		assertThat(output.getOut() + output.getErr()).contains("reason=unsupported_version");
	}

	@Test
	void missingRequiredFieldIsRedrivenToTheDlq(CapturedOutput output) throws Exception {
		sendRaw(poisonEnvelope("WAITLIST_PROMOTED", 1, "{\"eventId\":\"" + UUID.randomUUID() + "\"}"));
		redrive();
		assertThat(messageCount(dlqUrl)).isEqualTo(1);
		assertThat(notificationCount()).isZero();
		assertThat(output.getOut() + output.getErr()).contains("reason=malformed");
	}

	@Test
	void listingIsOwnerScopedNewestFirstAndMarkReadIsRepeatable() throws Exception {
		UUID eventId = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 2, NOW.minus(Duration.ofHours(1)),
				NOW.plus(Duration.ofHours(12)), NOW.plus(Duration.ofDays(1)));
		jdbcTemplate.update("update events set name = 'Rooftop Session' where id = ?", eventId);
		UUID owner = data.insertUser("USER");
		UUID other = data.insertUser("USER");
		ReservationResponse ownerReservation = reservationService.reserve(eventId, owner);
		ReservationResponse otherReservation = reservationService.reserve(eventId, other);
		UUID oldest = notificationId(1);
		UUID middle = notificationId(2);
		UUID newest = notificationId(3);
		insertNotification(oldest, owner, eventId, ownerReservation.id(), NOW);
		insertNotification(middle, owner, eventId, ownerReservation.id(), NOW.plus(Duration.ofMinutes(1)));
		insertNotification(newest, owner, eventId, ownerReservation.id(), NOW.plus(Duration.ofMinutes(2)));
		UUID foreign = notificationId(4);
		insertNotification(foreign, other, eventId, otherReservation.id(), NOW.plus(Duration.ofMinutes(3)));

		HttpResponse<String> unauthenticated = get("/api/notifications", null);
		assertError(unauthenticated, 401, "UNAUTHENTICATED");

		JsonNode firstPage = json(get("/api/notifications?page=0&size=2", token(owner)));
		assertThat(firstPage.path("size").asInt()).isEqualTo(2);
		assertThat(firstPage.path("totalElements").asLong()).isEqualTo(3);
		assertThat(ids(firstPage)).containsExactly(newest, middle);
		assertThat(firstPage.path("items").get(0).path("eventName").asString()).isEqualTo("Rooftop Session");
		assertThat(firstPage.path("items").get(0).path("type").asString()).isEqualTo("WAITLIST_PROMOTED");
		assertThat(firstPage.path("items").get(0).path("reservationId").asString())
				.isEqualTo(ownerReservation.id().toString());
		assertThat(firstPage.path("items").get(0).path("readAt").isNull()).isTrue();

		JsonNode secondPage = json(get("/api/notifications?page=1&size=2", token(owner)));
		assertThat(ids(secondPage)).containsExactly(oldest);
		JsonNode defaults = json(get("/api/notifications", token(owner)));
		assertThat(defaults.path("size").asInt()).isEqualTo(20);
		assertThat(ids(json(get("/api/notifications", token(other))))).containsExactly(foreign);

		assertError(get("/api/notifications?size=0", token(owner)), 400, "VALIDATION_FAILED");
		assertError(get("/api/notifications?size=101", token(owner)), 400, "VALIDATION_FAILED");

		clock.resetReads();
		NotificationResponse read = notificationService.markRead(newest, owner);
		assertThat(clock.reads()).isEqualTo(1);
		assertThat(read.readAt()).isEqualTo(NOW);
		clock.advance(Duration.ofHours(1));
		assertThat(notificationService.markRead(newest, owner).readAt()).isEqualTo(NOW);

		HttpResponse<String> viaHttp = post("/api/notifications/" + newest + "/read", token(owner));
		assertThat(viaHttp.statusCode()).isEqualTo(200);
		assertThat(json(viaHttp).path("readAt").asString()).isEqualTo(NOW.toString());
		assertError(post("/api/notifications/" + newest + "/read", token(other)), 404, "NOTIFICATION_NOT_FOUND");
		assertError(post("/api/notifications/" + UUID.randomUUID() + "/read", token(owner)), 404,
				"NOTIFICATION_NOT_FOUND");
	}

	@Test
	void equalOccurredAtBreaksTiesByIdDescending() throws Exception {
		UUID eventId = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 1, NOW.minus(Duration.ofHours(1)),
				NOW.plus(Duration.ofHours(12)), NOW.plus(Duration.ofDays(1)));
		UUID owner = data.insertUser("USER");
		ReservationResponse reservation = reservationService.reserve(eventId, owner);
		UUID lower = UUID.fromString("00000000-0000-7000-8000-000000000001");
		UUID higher = UUID.fromString("00000000-0000-7000-8000-000000000002");
		insertNotification(lower, owner, eventId, reservation.id(), NOW);
		insertNotification(higher, owner, eventId, reservation.id(), NOW);

		assertThat(ids(json(get("/api/notifications", token(owner))))).containsExactly(higher, lower);
	}

	@Test
	void httpCancelReachesTheOwnersNotificationList() throws Exception {
		UUID eventId = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 1, NOW.minus(Duration.ofHours(1)),
				NOW.plus(Duration.ofHours(12)), NOW.plus(Duration.ofDays(1)));
		jdbcTemplate.update("update events set name = 'Rooftop Session' where id = ?", eventId);
		UUID holder = data.insertUser("USER");
		UUID waiter = data.insertUser("USER");
		String reservationId = json(post("/api/events/" + eventId + "/reservations", token(holder))).path("id")
				.asString();
		assertThat(post("/api/events/" + eventId + "/waitlist", token(waiter)).statusCode()).isEqualTo(201);

		assertThat(post("/api/reservations/" + reservationId + "/cancel", token(holder)).statusCode()).isEqualTo(200);

		assertThat(data.outboxEventCount()).isEqualTo(1);
		publisher.publishAllDue();
		assertThat(consumer.pollOnce()).isEqualTo(1);
		JsonNode notifications = json(get("/api/notifications", token(waiter)));
		assertThat(notifications.path("totalElements").asLong()).isEqualTo(1);
		JsonNode item = notifications.path("items").get(0);
		assertThat(item.path("type").asString()).isEqualTo("WAITLIST_PROMOTED");
		assertThat(item.path("eventId").asString()).isEqualTo(eventId.toString());
		assertThat(item.path("eventName").asString()).isEqualTo("Rooftop Session");
		assertThat(item.path("reservationId").asString()).isNotBlank();
		assertThat(ids(json(get("/api/notifications", token(holder))))).isEmpty();
	}

	/** Creates a full event, promotes the only waiter, and returns the outbox event id. */
	private UUID promote() {
		return promoteReturningDetails().outboxId();
	}

	private Promoted promoteReturningDetails() {
		UUID eventId = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 1, NOW.minus(Duration.ofHours(1)),
				NOW.plus(Duration.ofHours(12)), NOW.plus(Duration.ofDays(1)));
		UUID holder = data.insertUser("USER");
		ReservationResponse reservation = reservationService.reserve(eventId, holder);
		UUID waiter = data.insertUser("USER");
		UUID entryId = waitlistService.join(eventId, waiter).id();
		reservationService.cancel(reservation.id(), holder);
		UUID outboxId = jdbcTemplate.queryForObject("select id from outbox_events where aggregate_id = ?", UUID.class,
				entryId);
		return new Promoted(eventId, outboxId);
	}

	private List<UUID> insertPending(int count) {
		List<UUID> ids = new ArrayList<>(count);
		OffsetDateTime due = NOW.atOffset(ZoneOffset.UTC);
		for (int i = 0; i < count; i++) {
			UUID id = UUID.randomUUID();
			ids.add(id);
			String json = "{\"userId\":\"" + id + "\",\"eventId\":\"" + id + "\",\"reservationId\":\"" + id
					+ "\",\"waitlistEntryId\":\"" + id + "\"}";
			jdbcTemplate.update("""
					insert into outbox_events (id, event_type, schema_version, aggregate_type, aggregate_id, data,
					    occurred_at, attempts, next_attempt_at)
					values (?, 'WAITLIST_PROMOTED', 1, 'waitlist_entry', ?, cast(? as jsonb), ?, 0, ?)
					""", id, id, json, due, due);
		}
		return ids;
	}

	private void insertNotification(UUID id, UUID userId, UUID eventId, UUID reservationId, Instant occurredAt) {
		jdbcTemplate.update("""
				insert into notifications (id, source_event_id, user_id, type, event_id, reservation_id,
				    occurred_at, created_at)
				values (?, ?, ?, 'WAITLIST_PROMOTED', ?, ?, ?, ?)
				""", id, UUID.randomUUID(), userId, eventId, reservationId, occurredAt.atOffset(ZoneOffset.UTC),
				NOW.atOffset(ZoneOffset.UTC));
	}

	private static UUID notificationId(int sequence) {
		return UUID.fromString("00000000-0000-7000-8000-00000000000" + sequence);
	}

	private ObjectNode envelopeFor(UUID outboxId) {
		String data = jdbcTemplate.queryForObject("select data::text from outbox_events where id = ?", String.class,
				outboxId);
		ObjectNode envelope = jsonMapper.createObjectNode();
		envelope.put("id", outboxId.toString());
		envelope.put("type", "WAITLIST_PROMOTED");
		envelope.put("version", 1);
		envelope.put("occurredAt", NOW.toString());
		envelope.set("data", jsonMapper.readTree(data));
		return envelope;
	}

	private String poisonEnvelope(String type, int version, String data) {
		return "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type + "\",\"version\":" + version
				+ ",\"occurredAt\":\"" + NOW + "\",\"trace\":\"" + CANARY + "\",\"data\":" + data + "}";
	}

	private void sendRaw(String body) {
		sqsClient.sendMessage(request -> request.queueUrl(mainQueueUrl).messageBody(body));
	}

	private void redrive() throws InterruptedException {
		for (int attempt = 0; attempt < 8 && messageCount(dlqUrl) == 0; attempt++) {
			consumer.pollOnce();
			if (messageCount(dlqUrl) > 0) {
				return;
			}
			Thread.sleep(1100);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<String> entryIds(Object argument) {
		Consumer<SendMessageBatchRequest.Builder> consumer = (Consumer<SendMessageBatchRequest.Builder>) argument;
		SendMessageBatchRequest.Builder builder = SendMessageBatchRequest.builder();
		consumer.accept(builder);
		return builder.build().entries().stream().map(SendMessageBatchRequestEntry::id).toList();
	}

	private Instant instant(String sql, UUID id) {
		return jdbcTemplate.queryForObject(sql, OffsetDateTime.class, id).toInstant();
	}

	private long notificationCount() {
		return jdbcTemplate.queryForObject("select count(*) from notifications", Long.class);
	}

	private long pendingCount() {
		return jdbcTemplate.queryForObject("select count(*) from outbox_events where published_at is null", Long.class);
	}

	private long publishedCount() {
		return jdbcTemplate.queryForObject("select count(*) from outbox_events where published_at is not null",
				Long.class);
	}

	private static int count(String value) {
		return value == null ? 0 : Integer.parseInt(value);
	}

	/** Visible messages plus messages currently hidden by the visibility timeout. */
	private int messageCount(String queueUrl) {
		var attributes = sqsClient.getQueueAttributes(request -> request.queueUrl(queueUrl)
				.attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
						QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
				.attributes();
		return count(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
				+ count(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
	}

	private List<Message> drain(String queueUrl) {
		List<Message> all = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			List<Message> batch = sqsClient.receiveMessage(request -> request.queueUrl(queueUrl)
					.maxNumberOfMessages(10)
					.waitTimeSeconds(0))
					.messages();
			if (batch.isEmpty()) {
				return all;
			}
			for (Message message : batch) {
				all.add(message);
				sqsClient.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
			}
		}
		throw new IllegalStateException("queue did not drain");
	}

	private String token(UUID user) {
		return jwtTokenService.issueAccessToken(user, Role.USER);
	}

	private void assertError(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		assertThat(json(response).path("code").asString()).isEqualTo(code);
	}

	private List<UUID> ids(JsonNode page) {
		List<UUID> ids = new ArrayList<>();
		page.path("items").forEach(item -> ids.add(UUID.fromString(item.path("id").asString())));
		return ids;
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

	private record Promoted(UUID eventId, UUID outboxId) {
	}

}
