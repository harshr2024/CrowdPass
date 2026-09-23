package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.crowdpass.ConcurrentAttempts.Result;
import com.crowdpass.exception.ApiException;
import com.crowdpass.reservation.ReservationResponse;
import com.crowdpass.reservation.ReservationService;
import com.crowdpass.reservation.WaitlistService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Concurrent waitlist behavior against real PostgreSQL. Uses the same bounded-worker, start-gate
 * harness as the reservation tests; every test verifies persisted state, including the full set of
 * seat and waitlist invariants, after all work has finished.
 */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest
class WaitlistConcurrencyIntegrationTest {

	private static final Instant NOW = MutableClock.Config.START;
	private static final Instant OPEN = NOW.minus(Duration.ofHours(1));
	private static final Instant CLOSE = NOW.plus(Duration.ofHours(12));
	private static final Instant STARTS = NOW.plus(Duration.ofDays(1));

	private static final String JOINED = "JOINED";
	private static final String RESERVED = "RESERVED";
	private static final String CANCELLED = "CANCELLED";
	private static final String LEFT = "LEFT";

	@Autowired
	private WaitlistService waitlistService;

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

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
	void manyUsersJoinFullEventConcurrently() throws Exception {
		Full full = fullEvent(10);
		List<UUID> joiners = data.insertUsers(500, "USER");

		Result result = run(150, joiners.stream().map(user -> join(full.event(), user)).toList());

		assertCompleted(result);
		assertThat(result.count(JOINED)).isEqualTo(500);
		assertThat(data.waitingUsers(full.event())).hasSize(500).containsExactlyInAnyOrderElementsOf(joiners);
		assertThat(jdbcTemplate.queryForObject(
				"select count(distinct queue_seq) from waitlist_entries where event_id = ?", Long.class, full.event()))
				.isEqualTo(500);
		assertConsistent(full.event());
	}

	@Test
	void sameUserJoiningConcurrentlyGetsOneEntry() throws Exception {
		Full full = fullEvent(1);
		UUID user = data.insertUser("USER");
		List<Callable<String>> attempts = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			attempts.add(join(full.event(), user));
		}

		Result result = run(50, attempts);

		assertCompleted(result);
		assertThat(result.count(JOINED)).isEqualTo(1);
		assertThat(result.count("ALREADY_WAITLISTED")).isEqualTo(49);
		assertThat(data.waitingUsers(full.event())).containsExactly(user);
		assertConsistent(full.event());
	}

	@RepeatedTest(10)
	void simultaneousCancellationsPromoteExactlyTheFirstWaitersOnce() throws Exception {
		Full full = fullEvent(20);
		List<UUID> waiters = joinInOrder(full.event(), 30);
		List<Callable<String>> cancels = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			cancels.add(cancel(full.reservations().get(i).id(), full.holders().get(i)));
		}

		Result result = run(10, cancels);

		assertCompleted(result);
		assertThat(result.count(CANCELLED)).isEqualTo(10);
		assertThat(data.promotedUsers(full.event())).containsExactlyElementsOf(waiters.subList(0, 10));
		assertThat(data.waitingUsers(full.event())).containsExactlyElementsOf(waiters.subList(10, 30));
		assertThat(data.state(full.event()).reservedCount()).isEqualTo(20);
		assertConsistent(full.event());
	}

	@Test
	void sequentialCancellationsPromoteInQueueOrder() {
		Full full = fullEvent(5);
		List<UUID> waiters = joinInOrder(full.event(), 5);

		for (int i = 0; i < 5; i++) {
			reservationService.cancel(full.reservations().get(i).id(), full.holders().get(i));
			assertThat(data.promotedUsers(full.event())).containsExactlyElementsOf(waiters.subList(0, i + 1));
		}
		assertConsistent(full.event());
	}

	@RepeatedTest(10)
	void directReservationsCannotJumpTheQueueDuringCancellations() throws Exception {
		Full full = fullEvent(20);
		List<UUID> waiters = joinInOrder(full.event(), 20);
		List<UUID> newcomers = data.insertUsers(100, "USER");
		List<Callable<String>> attempts = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			attempts.add(cancel(full.reservations().get(i).id(), full.holders().get(i)));
		}
		newcomers.forEach(user -> attempts.add(reserve(full.event(), user)));

		Result result = run(110, attempts);

		assertCompleted(result);
		assertThat(result.count(CANCELLED)).isEqualTo(10);
		assertThat(result.count("EVENT_FULL")).as("every direct reservation attempt").isEqualTo(100);
		assertThat(data.promotedUsers(full.event())).containsExactlyElementsOf(waiters.subList(0, 10));
		assertConsistent(full.event());
	}

	@RepeatedTest(20)
	void joinsRacingCancellationsNeverQueueWhileSeatsAreFree() throws Exception {
		Full full = fullEvent(10);
		List<UUID> joiners = data.insertUsers(50, "USER");
		List<Callable<String>> attempts = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			attempts.add(cancel(full.reservations().get(i).id(), full.holders().get(i)));
		}
		joiners.forEach(user -> attempts.add(join(full.event(), user)));

		Result result = run(attempts.size(), attempts);

		assertCompleted(result);
		assertThat(result.outcomes().keySet()).isSubsetOf(CANCELLED, JOINED, "SEAT_AVAILABLE");
		assertThat(result.count(CANCELLED)).isEqualTo(5);
		assertConsistent(full.event());
	}

	@RepeatedTest(30)
	void leaveRacingPromotionHasExactlyOneOutcome() throws Exception {
		Full full = fullEvent(1);
		List<UUID> waiters = joinInOrder(full.event(), 2);
		UUID head = waiters.get(0);
		UUID next = waiters.get(1);

		Result result = run(2, List.of(
				leave(full.event(), head),
				cancel(full.reservations().getFirst().id(), full.holders().getFirst())));

		assertCompleted(result);
		assertThat(result.count(CANCELLED)).isEqualTo(1);
		if (result.count("ALREADY_PROMOTED") == 1) {
			assertThat(data.promotedUsers(full.event())).containsExactly(head);
			assertThat(data.waitingUsers(full.event())).containsExactly(next);
		}
		else {
			assertThat(result.count(LEFT)).isEqualTo(1);
			assertThat(data.latestEntryStatus(full.event(), head)).isEqualTo("LEFT");
			assertThat(data.promotedUsers(full.event())).containsExactly(next);
		}
		assertThat(data.state(full.event()).reservedCount()).isEqualTo(1);
		assertConsistent(full.event());
	}

	@RepeatedTest(10)
	void promotedUserCancellingPromotesNextEvenUnderCompetingReservations() throws Exception {
		Full full = fullEvent(1);
		List<UUID> waiters = joinInOrder(full.event(), 3);
		reservationService.cancel(full.reservations().getFirst().id(), full.holders().getFirst());
		UUID promotedSeat = waitlistService.getMyEntry(full.event(), waiters.get(0)).reservationId();
		List<Callable<String>> attempts = new ArrayList<>();
		attempts.add(cancel(promotedSeat, waiters.get(0)));
		data.insertUsers(20, "USER").forEach(user -> attempts.add(reserve(full.event(), user)));

		Result result = run(attempts.size(), attempts);

		assertCompleted(result);
		assertThat(result.count("EVENT_FULL")).isEqualTo(20);
		assertThat(data.promotedUsers(full.event())).containsExactly(waiters.get(0), waiters.get(1));
		assertThat(data.waitingUsers(full.event())).containsExactly(waiters.get(2));
		assertConsistent(full.event());
	}

	@RepeatedTest(10)
	void mixedChurnPreservesAllInvariantsWithoutDeadlock() throws Exception {
		Full full = fullEvent(5);
		List<UUID> waiters = joinInOrder(full.event(), 10);
		List<UUID> joiners = data.insertUsers(20, "USER");
		List<UUID> reservers = data.insertUsers(20, "USER");
		List<Callable<String>> attempts = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			attempts.add(cancel(full.reservations().get(i).id(), full.holders().get(i)));
		}
		for (int i = 0; i < 3; i++) {
			attempts.add(leave(full.event(), waiters.get(i)));
			attempts.add(join(full.event(), waiters.get(i)));
		}
		joiners.forEach(user -> attempts.add(join(full.event(), user)));
		reservers.forEach(user -> attempts.add(reserve(full.event(), user)));

		Result result = run(attempts.size(), attempts);

		assertCompleted(result);
		assertThat(result.outcomes().keySet()).isSubsetOf(CANCELLED, LEFT, JOINED, RESERVED, "ALREADY_WAITLISTED",
				"ALREADY_PROMOTED", "SEAT_AVAILABLE", "EVENT_FULL", "ALREADY_RESERVED");
		assertConsistent(full.event());
	}

	private record Full(UUID event, List<UUID> holders, List<ReservationResponse> reservations) {
	}

	private Full fullEvent(int capacity) {
		UUID event = data.insertEvent(organizer, "PUBLISHED", capacity, OPEN, CLOSE, STARTS);
		List<UUID> holders = data.insertUsers(capacity, "USER");
		List<ReservationResponse> reservations = holders.stream().map(h -> reservationService.reserve(event, h))
				.toList();
		return new Full(event, holders, reservations);
	}

	private List<UUID> joinInOrder(UUID event, int count) {
		List<UUID> users = data.insertUsers(count, "USER");
		users.forEach(user -> waitlistService.join(event, user));
		return users;
	}

	private Callable<String> join(UUID event, UUID user) {
		return outcome(() -> waitlistService.join(event, user), JOINED);
	}

	private Callable<String> leave(UUID event, UUID user) {
		return outcome(() -> waitlistService.leave(event, user), LEFT);
	}

	private Callable<String> reserve(UUID event, UUID user) {
		return outcome(() -> reservationService.reserve(event, user), RESERVED);
	}

	private Callable<String> cancel(UUID reservation, UUID user) {
		return () -> reservationService.cancel(reservation, user).status().name();
	}

	private static Callable<String> outcome(Runnable operation, String success) {
		return () -> {
			try {
				operation.run();
				return success;
			}
			catch (ApiException ex) {
				return ex.getCode();
			}
		};
	}

	private Result run(int workers, List<Callable<String>> attempts) throws InterruptedException {
		HikariDataSource hikari = (HikariDataSource) dataSource;
		return ConcurrentAttempts.run(workers, attempts,
				() -> hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());
	}

	private static void assertCompleted(Result result) {
		assertThat(result.finished()).as("all attempts finished before the timeout").isTrue();
		assertThat(result.unexpected()).as("unexpected exceptions").isEmpty();
	}

	private void assertConsistent(UUID event) {
		assertThat(data.invariantViolations(event)).as("seat and waitlist invariants").isEmpty();
	}

}
