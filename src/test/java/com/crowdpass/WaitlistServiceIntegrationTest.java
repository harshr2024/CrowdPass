package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import com.crowdpass.reservation.WaitlistEntryResponse;
import com.crowdpass.reservation.WaitlistService;
import com.crowdpass.reservation.waitlist.WaitlistStatus;

import io.micrometer.core.instrument.MeterRegistry;

/** Single-threaded waitlist behavior against PostgreSQL, with a controllable clock. */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest
class WaitlistServiceIntegrationTest {

	private static final Instant NOW = MutableClock.Config.START;
	private static final Instant OPEN = NOW.minus(Duration.ofHours(1));
	private static final Instant CLOSE = NOW.plus(Duration.ofHours(12));
	private static final Instant STARTS = NOW.plus(Duration.ofDays(1));
	private static final Duration MICRO = Duration.ofNanos(1_000);

	@Autowired
	private WaitlistService waitlistService;

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MeterRegistry meterRegistry;

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
	void joiningFullEventQueuesUsersInOrderWithDerivedPositions() {
		UUID event = fullEvent(1).event();
		List<UUID> waiters = data.insertUsers(3, "USER");

		List<WaitlistEntryResponse> entries = waiters.stream().map(user -> waitlistService.join(event, user)).toList();

		assertThat(entries).extracting(WaitlistEntryResponse::status).containsOnly(WaitlistStatus.WAITING);
		assertThat(entries).extracting(WaitlistEntryResponse::position).containsExactly(1L, 2L, 3L);
		assertThat(entries).extracting(WaitlistEntryResponse::joinedAt).containsOnly(NOW);
		assertThat(entries).extracting(WaitlistEntryResponse::waitlistClosed).containsOnly(false);
		assertThat(data.waitingUsers(event)).containsExactlyElementsOf(waiters);
		assertThat(data.invariantViolations(event)).isEmpty();
	}

	@Test
	void joinIsRejectedWhileASeatIsAvailable() {
		UUID event = openEvent(2);
		reservationService.reserve(event, data.insertUser("USER"));

		assertCode("SEAT_AVAILABLE", () -> waitlistService.join(event, data.insertUser("USER")));
		assertThat(data.waitingUsers(event)).isEmpty();
	}

	@Test
	void holderCannotJoinAndWaiterCannotJoinTwice() {
		Full full = fullEvent(1);
		UUID waiter = data.insertUser("USER");
		waitlistService.join(full.event(), waiter);

		assertCode("ALREADY_RESERVED", () -> waitlistService.join(full.event(), full.holders().getFirst()));
		assertCode("ALREADY_WAITLISTED", () -> waitlistService.join(full.event(), waiter));
		assertThat(data.waitingUsers(full.event())).containsExactly(waiter);
	}

	@ParameterizedTest
	@ValueSource(strings = { "DRAFT", "CANCELLED", "MISSING" })
	void joinRequiresPublishedEvent(String status) {
		UUID event = status.equals("MISSING") ? UUID.randomUUID()
				: data.insertEvent(organizer, status, 1, OPEN, CLOSE, STARTS);

		assertCode("EVENT_NOT_FOUND", () -> waitlistService.join(event, data.insertUser("USER")));
	}

	@Test
	void joinRequiresOpenRegistration() {
		UUID event = fullEvent(1).event();

		clock.set(OPEN.minus(MICRO));
		assertCode("REGISTRATION_NOT_OPEN", () -> waitlistService.join(event, data.insertUser("USER")));
		clock.set(CLOSE);
		assertCode("REGISTRATION_CLOSED", () -> waitlistService.join(event, data.insertUser("USER")));
		clock.set(CLOSE.minus(MICRO));
		assertThat(waitlistService.join(event, data.insertUser("USER")).status()).isEqualTo(WaitlistStatus.WAITING);
	}

	@Test
	void leaveIsIdempotentAndPreservesHistory() {
		UUID event = fullEvent(1).event();
		UUID waiter = data.insertUser("USER");
		waitlistService.join(event, waiter);
		clock.advance(Duration.ofMinutes(1));

		WaitlistEntryResponse left = waitlistService.leave(event, waiter);
		clock.advance(Duration.ofMinutes(1));
		WaitlistEntryResponse again = waitlistService.leave(event, waiter);

		assertThat(left.status()).isEqualTo(WaitlistStatus.LEFT);
		assertThat(left.leftAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
		assertThat(left.position()).isNull();
		assertThat(again).isEqualTo(left);
		assertThat(data.latestEntryStatus(event, waiter)).isEqualTo("LEFT");
	}

	@Test
	void leaveWithoutEntryOrEventIsNotFound() {
		UUID event = fullEvent(1).event();

		assertCode("WAITLIST_ENTRY_NOT_FOUND", () -> waitlistService.leave(event, data.insertUser("USER")));
		assertCode("WAITLIST_ENTRY_NOT_FOUND", () -> waitlistService.leave(UUID.randomUUID(), data.insertUser("USER")));
		assertCode("WAITLIST_ENTRY_NOT_FOUND", () -> waitlistService.getMyEntry(event, data.insertUser("USER")));
	}

	@Test
	void cancellationPromotesHeadWithoutChangingReservedCount() {
		double promotionsBefore = meterRegistry.counter("crowdpass.waitlist.promotions").count();
		Full full = fullEvent(1);
		UUID first = data.insertUser("USER");
		UUID second = data.insertUser("USER");
		waitlistService.join(full.event(), first);
		waitlistService.join(full.event(), second);
		clock.advance(Duration.ofMinutes(10));
		Instant cancelTime = NOW.plus(Duration.ofMinutes(10));

		ReservationResponse cancelled = reservationService.cancel(full.reservations().getFirst().id(),
				full.holders().getFirst());

		assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
		WaitlistEntryResponse promoted = waitlistService.getMyEntry(full.event(), first);
		assertThat(promoted.status()).isEqualTo(WaitlistStatus.PROMOTED);
		assertThat(promoted.promotedAt()).isEqualTo(cancelTime);
		assertThat(promoted.position()).isNull();
		ReservationResponse seat = reservationService.getReservation(promoted.reservationId(), first);
		assertThat(seat.status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(seat.createdAt()).isEqualTo(cancelTime);
		assertThat(waitlistService.getMyEntry(full.event(), second).position()).isEqualTo(1L);
		EventState state = data.state(full.event());
		assertThat(state.reservedCount()).isEqualTo(1);
		assertThat(data.invariantViolations(full.event())).isEmpty();
		assertThat(meterRegistry.counter("crowdpass.waitlist.promotions").count() - promotionsBefore).isEqualTo(1);
	}

	@Test
	void promotedUserCannotLeaveAndTheirCancellationPromotesTheNext() {
		Full full = fullEvent(1);
		UUID first = data.insertUser("USER");
		UUID second = data.insertUser("USER");
		waitlistService.join(full.event(), first);
		waitlistService.join(full.event(), second);
		reservationService.cancel(full.reservations().getFirst().id(), full.holders().getFirst());
		UUID firstSeat = waitlistService.getMyEntry(full.event(), first).reservationId();

		assertCode("ALREADY_PROMOTED", () -> waitlistService.leave(full.event(), first));
		reservationService.cancel(firstSeat, first);

		assertThat(data.promotedUsers(full.event())).containsExactly(first, second);
		assertThat(data.state(full.event()).reservedCount()).isEqualTo(1);
		assertThat(data.invariantViolations(full.event())).isEmpty();
	}

	@Test
	void rejoiningAfterLeavingOrAfterPromotionGoesToTheBack() {
		Full full = fullEvent(2);
		UUID a = data.insertUser("USER");
		UUID b = data.insertUser("USER");
		UUID c = data.insertUser("USER");
		waitlistService.join(full.event(), a);
		waitlistService.join(full.event(), b);
		waitlistService.join(full.event(), c);

		waitlistService.leave(full.event(), a);
		assertThat(waitlistService.join(full.event(), a).position()).isEqualTo(3L);
		assertThat(data.waitingUsers(full.event())).containsExactly(b, c, a);

		reservationService.cancel(full.reservations().getFirst().id(), full.holders().getFirst());
		UUID bSeat = waitlistService.getMyEntry(full.event(), b).reservationId();
		reservationService.cancel(bSeat, b);
		assertThat(data.waitingUsers(full.event())).containsExactly(a);
		assertThat(waitlistService.join(full.event(), b).position()).isEqualTo(2L);
		assertThat(data.waitingUsers(full.event())).containsExactly(a, b);
		assertThat(data.invariantViolations(full.event())).isEmpty();
	}

	@Test
	void cancellationWithoutWaitersReleasesSeatOnce() {
		Full full = fullEvent(2);

		reservationService.cancel(full.reservations().getFirst().id(), full.holders().getFirst());

		assertThat(data.state(full.event()).reservedCount()).isEqualTo(1);
		assertThat(data.invariantViolations(full.event())).isEmpty();
	}

	@Test
	void promotionContinuesAfterRegistrationClosesUntilStart() {
		Full full = fullEvent(2);
		UUID first = data.insertUser("USER");
		UUID second = data.insertUser("USER");
		waitlistService.join(full.event(), first);
		waitlistService.join(full.event(), second);

		clock.set(CLOSE.plus(Duration.ofHours(1)));
		reservationService.cancel(full.reservations().get(0).id(), full.holders().get(0));
		assertThat(data.promotedUsers(full.event())).containsExactly(first);

		clock.set(STARTS);
		assertCode("CANCELLATION_CLOSED",
				() -> reservationService.cancel(full.reservations().get(1).id(), full.holders().get(1)));
		WaitlistEntryResponse stillWaiting = waitlistService.getMyEntry(full.event(), second);
		assertThat(stillWaiting.status()).isEqualTo(WaitlistStatus.WAITING);
		assertThat(stillWaiting.waitlistClosed()).isTrue();
		assertThat(stillWaiting.position()).isEqualTo(1L);
	}

	@Test
	void positionIsASnapshotThatMovesWhenSomeoneAheadLeaves() {
		UUID event = fullEvent(1).event();
		List<UUID> waiters = data.insertUsers(3, "USER");
		waiters.forEach(user -> waitlistService.join(event, user));

		assertThat(waitlistService.getMyEntry(event, waiters.get(2)).position()).isEqualTo(3L);
		waitlistService.leave(event, waiters.get(0));
		assertThat(waitlistService.getMyEntry(event, waiters.get(2)).position()).isEqualTo(2L);
	}

	@Test
	void waiterAttemptingDirectReservationSeesEventFull() {
		UUID event = fullEvent(1).event();
		UUID waiter = data.insertUser("USER");
		waitlistService.join(event, waiter);

		assertCode("EVENT_FULL", () -> reservationService.reserve(event, waiter));
		assertThat(data.invariantViolations(event)).isEmpty();
	}

	@Test
	void readsClockExactlyOncePerOperation() {
		Full full = fullEvent(1);
		UUID waiter = data.insertUser("USER");
		UUID other = data.insertUser("USER");

		clock.resetReads();
		waitlistService.join(full.event(), waiter);
		assertThat(clock.resetReads()).as("join").isEqualTo(1);
		waitlistService.join(full.event(), other);
		clock.resetReads();
		waitlistService.getMyEntry(full.event(), waiter);
		assertThat(clock.resetReads()).as("status").isEqualTo(1);
		reservationService.cancel(full.reservations().getFirst().id(), full.holders().getFirst());
		assertThat(clock.resetReads()).as("cancel with promotion").isEqualTo(1);
		waitlistService.leave(full.event(), other);
		assertThat(clock.resetReads()).as("leave").isEqualTo(1);
	}

	@Test
	void cancelledEventReleasesSeatWithoutPromoting() {
		Full full = fullEvent(1);
		UUID waiter = data.insertUser("USER");
		waitlistService.join(full.event(), waiter);
		data.cancelEvent(full.event());

		reservationService.cancel(full.reservations().getFirst().id(), full.holders().getFirst());

		assertThat(data.state(full.event()).reservedCount()).isZero();
		assertThat(data.latestEntryStatus(full.event(), waiter)).isEqualTo("WAITING");
		assertThat(waitlistService.getMyEntry(full.event(), waiter).waitlistClosed()).isTrue();
	}

	@Test
	void corruptWaitlistStateFailsCancellationAndRollsBackEverything() {
		Full full = fullEvent(2);
		UUID holder = full.holders().get(0);
		UUID corrupt = full.holders().get(1);
		// Break I2 directly in the database: a CONFIRMED holder is also WAITING.
		jdbcTemplate.update("""
				insert into waitlist_entries (id, event_id, user_id, status, created_at)
				values (?, ?, ?, 'WAITING', ?)
				""", UUID.randomUUID(), full.event(), corrupt, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));

		assertThatThrownBy(() -> reservationService.cancel(full.reservations().get(0).id(), holder))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already holds a reservation");

		assertThat(data.reservationStatus(full.reservations().get(0).id())).isEqualTo("CONFIRMED");
		assertThat(data.latestEntryStatus(full.event(), corrupt)).isEqualTo("WAITING");
		assertThat(data.state(full.event()).reservedCount()).isEqualTo(2);
	}

	private record Full(UUID event, List<UUID> holders, List<ReservationResponse> reservations) {
	}

	private Full fullEvent(int capacity) {
		UUID event = openEvent(capacity);
		List<UUID> holders = data.insertUsers(capacity, "USER");
		List<ReservationResponse> reservations = holders.stream().map(h -> reservationService.reserve(event, h))
				.toList();
		return new Full(event, holders, reservations);
	}

	private UUID openEvent(int capacity) {
		return data.insertEvent(organizer, "PUBLISHED", capacity, OPEN, CLOSE, STARTS);
	}

	private static void assertCode(String code, ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(code));
	}

}
