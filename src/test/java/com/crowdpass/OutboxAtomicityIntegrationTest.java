package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;

import com.crowdpass.exception.ApiException;
import com.crowdpass.outbox.OutboxWriter;
import com.crowdpass.reservation.ReservationResponse;
import com.crowdpass.reservation.ReservationService;
import com.crowdpass.reservation.WaitlistService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The outbox event commits if and only if the promotion commits. */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest
class OutboxAtomicityIntegrationTest {

	private static final Instant NOW = MutableClock.Config.START;

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private WaitlistService waitlistService;

	@Autowired
	private OutboxWriter outboxWriter;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private PlatformTransactionManager transactionManager;

	/** Throws on send, so a reservation transaction that called SQS would fail these tests. */
	@MockitoBean
	private software.amazon.awssdk.services.sqs.SqsClient sqsClient;

	private ReservationTestData data;
	private UUID event;
	private UUID holder;
	private ReservationResponse holderReservation;

	@BeforeEach
	void setUp() {
		data = new ReservationTestData(jdbcTemplate);
		data.reset();
		IllegalStateException forbidden = new IllegalStateException(
				"SQS must not be called from the reservation transaction");
		lenient().doThrow(forbidden).when(sqsClient)
				.sendMessage(any(software.amazon.awssdk.services.sqs.model.SendMessageRequest.class));
		lenient().doThrow(forbidden).when(sqsClient).sendMessage(any(Consumer.class));
		lenient().doThrow(forbidden).when(sqsClient)
				.sendMessageBatch(any(software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest.class));
		lenient().doThrow(forbidden).when(sqsClient).sendMessageBatch(any(Consumer.class));
		clock.set(NOW);
		event = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 1, NOW.minus(Duration.ofHours(1)),
				NOW.plus(Duration.ofHours(12)), NOW.plus(Duration.ofDays(1)));
		holder = data.insertUser("USER");
		holderReservation = reservationService.reserve(event, holder);
	}

	@Test
	void promotionWritesExactlyOneEventWithIdentifiersOnly() {
		UUID waiter = data.insertUser("USER");
		UUID entryId = waitlistService.join(event, waiter).id();
		clock.advance(Duration.ofMinutes(3));

		reservationService.cancel(holderReservation.id(), holder);

		Map<String, Object> row = jdbcTemplate.queryForMap("select * from outbox_events");
		UUID promotedReservation = waitlistService.getMyEntry(event, waiter).reservationId();
		assertThat(row).containsEntry("event_type", "WAITLIST_PROMOTED")
				.containsEntry("schema_version", 1)
				.containsEntry("aggregate_type", "waitlist_entry")
				.containsEntry("aggregate_id", entryId)
				.containsEntry("attempts", 0)
				.containsEntry("published_at", null)
				.containsEntry("last_error", null);
		Instant occurredAt = ((OffsetDateTime) jdbcTemplate.queryForObject("select occurred_at from outbox_events",
				OffsetDateTime.class)).toInstant();
		assertThat(occurredAt).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
		assertThat(((UUID) row.get("id")).version()).isEqualTo(7);

		JsonNode body = jsonMapper.readTree(row.get("data").toString());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("userId", "eventId", "reservationId",
				"waitlistEntryId");
		assertThat(body.path("userId").asString()).isEqualTo(waiter.toString());
		assertThat(body.path("eventId").asString()).isEqualTo(event.toString());
		assertThat(body.path("reservationId").asString()).isEqualTo(promotedReservation.toString());
		assertThat(body.path("waitlistEntryId").asString()).isEqualTo(entryId.toString());
		assertThat(data.invariantViolations(event)).isEmpty();
	}

	@Test
	void cancellationWithoutWaiterWritesNoEvent() {
		reservationService.cancel(holderReservation.id(), holder);

		assertThat(data.outboxEventCount()).isZero();
	}

	@Test
	void rollingBackTheDomainTransactionRemovesItsEvent() {
		UUID waiter = data.insertUser("USER");
		waitlistService.join(event, waiter);
		TransactionTemplate outer = new TransactionTemplate(transactionManager);

		outer.executeWithoutResult(status -> {
			reservationService.cancel(holderReservation.id(), holder);
			assertThat(data.outboxEventCount()).as("visible inside the transaction").isEqualTo(1);
			status.setRollbackOnly();
		});

		assertThat(data.outboxEventCount()).isZero();
		assertThat(data.latestEntryStatus(event, waiter)).isEqualTo("WAITING");
		assertThat(data.reservationStatus(holderReservation.id())).isEqualTo("CONFIRMED");
	}

	@Test
	void failedCancellationWritesNoEvent() {
		waitlistService.join(event, data.insertUser("USER"));
		clock.set(NOW.plus(Duration.ofDays(1)));

		assertThatThrownBy(() -> reservationService.cancel(holderReservation.id(), holder))
				.isInstanceOf(ApiException.class);
		assertThat(data.outboxEventCount()).isZero();
	}

	@Test
	void outboxWriterRefusesToRunOutsideATransaction() {
		assertThatThrownBy(() -> outboxWriter.append("TEST", 1, "test", UUID.randomUUID(), Map.of("k", "v"), NOW))
				.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(data.outboxEventCount()).isZero();
	}

	@Test
	void corruptPromotionRollsBackWithoutAnEvent() {
		UUID corrupt = data.insertUser("USER");
		// A waiter who already holds a reservation for the event (I2 broken directly in the database).
		UUID second = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 2, NOW.minus(Duration.ofHours(1)),
				NOW.plus(Duration.ofHours(12)), NOW.plus(Duration.ofDays(1)));
		ReservationResponse first = reservationService.reserve(second, data.insertUser("USER"));
		reservationService.reserve(second, corrupt);
		jdbcTemplate.update("insert into waitlist_entries (id, event_id, user_id, status, created_at) values (?, ?, ?, 'WAITING', ?)",
				UUID.randomUUID(), second, corrupt, NOW.atOffset(ZoneOffset.UTC));
		List<Map<String, Object>> firstOwner = jdbcTemplate.queryForList("select user_id from reservations where id = ?",
				first.id());

		assertThatThrownBy(() -> reservationService.cancel(first.id(), (UUID) firstOwner.getFirst().get("user_id")))
				.isInstanceOf(IllegalStateException.class);
		assertThat(data.outboxEventCount()).isZero();
	}

}
