package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.crowdpass.ReservationTestData.EventState;
import com.crowdpass.exception.ApiException;
import com.crowdpass.reservation.ReservationResponse;
import com.crowdpass.reservation.ReservationService;
import com.crowdpass.reservation.ReservationStatus;

/** Single-threaded reservation behavior against PostgreSQL, with a controllable clock. */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest
class ReservationServiceIntegrationTest {

	private static final Instant NOW = MutableClock.Config.START;
	private static final Instant OPEN = NOW.minus(Duration.ofHours(1));
	private static final Instant STARTS = NOW.plus(Duration.ofDays(1));
	private static final Duration MICRO = Duration.ofNanos(1_000);

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private ReservationTestData data;
	private UUID organizer;

	@BeforeEach
	void setUp() {
		data = new ReservationTestData(jdbcTemplate);
		data.reset();
		clock.set(NOW);
		organizer = data.insertUser("ORGANIZER");
	}

	@Test
	void reservesSeatAndRecordsConfirmedReservation() {
		UUID event = publishedEvent(10);
		UUID user = data.insertUser("USER");

		ReservationResponse reservation = reservationService.reserve(event, user);

		assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(reservation.eventId()).isEqualTo(event);
		assertThat(reservation.createdAt()).isEqualTo(NOW);
		assertThat(reservation.id().version()).isEqualTo(7);
		assertState(event, 1, 1, 0);
	}

	@Test
	void readsClockExactlyOncePerOperation() {
		UUID event = publishedEvent(1);
		UUID user = data.insertUser("USER");
		UUID other = data.insertUser("USER");

		clock.resetReads();
		ReservationResponse reservation = reservationService.reserve(event, user);
		assertThat(clock.resetReads()).as("successful reserve").isEqualTo(1);

		assertCode("EVENT_FULL", () -> reservationService.reserve(event, other));
		assertThat(clock.resetReads()).as("failed reserve with classification").isEqualTo(1);

		reservationService.cancel(reservation.id(), user);
		assertThat(clock.resetReads()).as("cancel").isEqualTo(1);
	}

	@Test
	void registrationWindowIsHalfOpenAtExactBoundaries() {
		UUID event = publishedEvent(10);

		clock.set(OPEN.minus(MICRO));
		assertCode("REGISTRATION_NOT_OPEN", () -> reservationService.reserve(event, data.insertUser("USER")));

		clock.set(OPEN);
		reservationService.reserve(event, data.insertUser("USER"));

		clock.set(STARTS.minus(MICRO));
		reservationService.reserve(event, data.insertUser("USER"));

		clock.set(STARTS);
		assertCode("REGISTRATION_CLOSED", () -> reservationService.reserve(event, data.insertUser("USER")));

		assertState(event, 2, 2, 0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "DRAFT", "CANCELLED", "MISSING" })
	void nonPublishedOrMissingEventIsNotFound(String status) {
		UUID event = status.equals("MISSING") ? UUID.randomUUID()
				: data.insertEvent(organizer, status, 10, OPEN, STARTS);

		assertCode("EVENT_NOT_FOUND", () -> reservationService.reserve(event, data.insertUser("USER")));

		if (!status.equals("MISSING")) {
			assertState(event, 0, 0, 0);
		}
	}

	@Test
	void fullEventRejectsOtherUsers() {
		UUID event = publishedEvent(1);
		reservationService.reserve(event, data.insertUser("USER"));

		assertCode("EVENT_FULL", () -> reservationService.reserve(event, data.insertUser("USER")));
		assertState(event, 1, 1, 0);
	}

	@Test
	void holderRetryingOnFullEventIsAlreadyReserved() {
		UUID event = publishedEvent(1);
		UUID user = data.insertUser("USER");
		reservationService.reserve(event, user);

		assertCode("ALREADY_RESERVED", () -> reservationService.reserve(event, user));
		assertState(event, 1, 1, 0);
	}

	@Test
	void duplicateInsertRollsBackSeatClaim() {
		UUID event = publishedEvent(10);
		UUID user = data.insertUser("USER");
		reservationService.reserve(event, user);

		// Seats remain, so the UPDATE matches and increments; the INSERT then hits the unique index.
		assertCode("ALREADY_RESERVED", () -> reservationService.reserve(event, user));
		assertState(event, 1, 1, 0);
	}

	@Test
	void failedInsertForUnknownUserRollsBackSeatClaim() {
		UUID event = publishedEvent(10);

		assertCode("UNAUTHENTICATED", () -> reservationService.reserve(event, UUID.randomUUID()));
		assertState(event, 0, 0, 0);
	}

	@Test
	void userCanReserveAgainAfterCancellingAndHistoryIsKept() {
		UUID event = publishedEvent(10);
		UUID user = data.insertUser("USER");
		ReservationResponse first = reservationService.reserve(event, user);
		reservationService.cancel(first.id(), user);

		reservationService.reserve(event, user);

		EventState state = data.state(event);
		assertThat(state.cancelled()).isEqualTo(1);
		assertState(event, 1, 1, 1);
	}

	@Test
	void organizerMayReserveOwnEvent() {
		UUID event = publishedEvent(10);

		assertThat(reservationService.reserve(event, organizer).status()).isEqualTo(ReservationStatus.CONFIRMED);
	}

	@Test
	void cancelReleasesSeatOnceAndRepeatedCancelReturnsExistingState() {
		UUID event = publishedEvent(10);
		UUID user = data.insertUser("USER");
		ReservationResponse reservation = reservationService.reserve(event, user);
		clock.advance(Duration.ofMinutes(5));
		Instant cancelTime = NOW.plus(Duration.ofMinutes(5));

		ReservationResponse cancelled = reservationService.cancel(reservation.id(), user);
		clock.advance(Duration.ofMinutes(5));
		ReservationResponse again = reservationService.cancel(reservation.id(), user);

		assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
		assertThat(cancelled.cancelledAt()).isEqualTo(cancelTime);
		assertThat(again).isEqualTo(cancelled);
		assertState(event, 0, 0, 1);
	}

	@Test
	void onlyOwnerCanReadOrCancel() {
		UUID event = publishedEvent(10);
		UUID owner = data.insertUser("USER");
		UUID other = data.insertUser("USER");
		ReservationResponse reservation = reservationService.reserve(event, owner);

		assertCode("RESERVATION_NOT_FOUND", () -> reservationService.getReservation(reservation.id(), other));
		assertCode("RESERVATION_NOT_FOUND", () -> reservationService.cancel(reservation.id(), other));
		assertCode("RESERVATION_NOT_FOUND", () -> reservationService.cancel(UUID.randomUUID(), owner));
		assertThat(data.reservationStatus(reservation.id())).isEqualTo("CONFIRMED");
		assertState(event, 1, 1, 0);
	}

	@Test
	void cancellationCutoffIsStartTime() {
		UUID event = publishedEvent(10);
		UUID early = data.insertUser("USER");
		UUID late = data.insertUser("USER");
		ReservationResponse earlyReservation = reservationService.reserve(event, early);
		ReservationResponse lateReservation = reservationService.reserve(event, late);

		clock.set(STARTS.minus(MICRO));
		assertThat(reservationService.cancel(earlyReservation.id(), early).status())
				.isEqualTo(ReservationStatus.CANCELLED);

		clock.set(STARTS);
		assertCode("CANCELLATION_CLOSED", () -> reservationService.cancel(lateReservation.id(), late));

		assertThat(data.reservationStatus(lateReservation.id())).isEqualTo("CONFIRMED");
		assertState(event, 1, 1, 1);
	}

	@Test
	void repeatedCancelAfterStartStillReturnsExistingState() {
		UUID event = publishedEvent(10);
		UUID user = data.insertUser("USER");
		ReservationResponse reservation = reservationService.reserve(event, user);
		ReservationResponse cancelled = reservationService.cancel(reservation.id(), user);

		clock.set(STARTS.plus(Duration.ofHours(1)));

		assertThat(reservationService.cancel(reservation.id(), user)).isEqualTo(cancelled);
	}

	@Test
	void cancelRollsBackWhenCounterHasDrifted() {
		UUID event = publishedEvent(10);
		UUID user = data.insertUser("USER");
		ReservationResponse reservation = reservationService.reserve(event, user);
		data.setReservedCount(event, 0);

		assertThatThrownBy(() -> reservationService.cancel(reservation.id(), user))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("drift");

		assertThat(data.reservationStatus(reservation.id())).isEqualTo("CONFIRMED");
		assertThat(jdbcTemplate.queryForObject("select cancelled_at is null from reservations where id = ?",
				Boolean.class, reservation.id())).isTrue();
	}

	private UUID publishedEvent(int capacity) {
		return data.insertEvent(organizer, "PUBLISHED", capacity, OPEN, STARTS);
	}

	private void assertState(UUID event, int reservedCount, long confirmed, long cancelled) {
		EventState state = data.state(event);
		assertThat(state.reservedCount()).as("reserved_count").isEqualTo(reservedCount);
		assertThat(state.confirmed()).as("CONFIRMED rows").isEqualTo(confirmed);
		assertThat(state.cancelled()).as("CANCELLED rows").isEqualTo(cancelled);
		assertThat(state.maxConfirmedPerUser()).as("max CONFIRMED per user").isLessThanOrEqualTo(1);
	}

	private static void assertCode(String code, ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(code));
	}

}
