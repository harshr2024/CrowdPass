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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.crowdpass.ConcurrentAttempts.Result;
import com.crowdpass.ReservationTestData.EventState;
import com.crowdpass.exception.ApiException;
import com.crowdpass.reservation.ReservationResponse;
import com.crowdpass.reservation.ReservationService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Concurrent reservation behavior against real PostgreSQL. Attempts come from a bounded worker
 * pool much larger than the Hikari connection pool (default size, not tuned), so the pool stays
 * saturated: many attempts compete, while at most {@code maximumPoolSize} transactions run in
 * PostgreSQL at any instant. Every test verifies persisted state after all work has finished.
 */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest
class ReservationConcurrencyIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(ReservationConcurrencyIntegrationTest.class);

	private static final Instant NOW = MutableClock.Config.START;
	private static final Instant OPEN = NOW.minus(Duration.ofHours(1));
	private static final Instant STARTS = NOW.plus(Duration.ofDays(1));
	private static final String SUCCESS = "SUCCESS";
	private static final String CANCELLED = "CANCELLED";

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
	void thousandCompetingAttemptsNeverOversellHundredSeats() throws Exception {
		UUID event = publishedEvent(100);
		List<UUID> users = data.insertUsers(1_000, "USER");

		Result result = run(150, users.stream().map(user -> reserve(event, user)).toList());

		log.info("1000 attempts / 150 workers / pool {}: outcomes={} peakInFlight={} peakAwaitingConnection={}",
				hikari().getMaximumPoolSize(), result.outcomes(), result.peakInFlight(),
				result.peakAwaitingConnection());
		assertCompleted(result);
		assertThat(result.count(SUCCESS)).isEqualTo(100);
		assertThat(result.count("EVENT_FULL")).isEqualTo(900);
		assertThat(result.peakInFlight()).as("attempts overlapped beyond the pool size")
				.isGreaterThan(hikari().getMaximumPoolSize());

		EventState state = data.state(event);
		assertThat(state.confirmed()).isEqualTo(100);
		assertThat(state.distinctConfirmedUsers()).isEqualTo(100);
		assertThat(state.reservedCount()).isEqualTo(100);
		assertThat(state.cancelled()).isZero();
		assertThat(countReservations(event)).as("no rows beyond the successful ones").isEqualTo(100);
	}

	@RepeatedTest(50)
	void capacityOneHasExactlyOneWinner() throws Exception {
		UUID event = publishedEvent(1);
		List<UUID> users = data.insertUsers(10, "USER");

		Result result = run(10, users.stream().map(user -> reserve(event, user)).toList());

		assertCompleted(result);
		assertThat(result.count(SUCCESS)).isEqualTo(1);
		assertThat(result.count("EVENT_FULL")).isEqualTo(9);
		assertConsistent(event, 1);
	}

	@Test
	void sameUserSimultaneousAttemptsYieldOneReservation() throws Exception {
		UUID event = publishedEvent(100);
		UUID user = data.insertUser("USER");
		List<Callable<String>> attempts = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			attempts.add(reserve(event, user));
		}

		Result result = run(50, attempts);

		assertCompleted(result);
		assertThat(result.count(SUCCESS)).isEqualTo(1);
		assertThat(result.count("ALREADY_RESERVED")).isEqualTo(49);
		assertConsistent(event, 1);
	}

	@Test
	void mixedDuplicateAndCompetingAttemptsRespectBothInvariants() throws Exception {
		UUID event = publishedEvent(100);
		List<UUID> users = data.insertUsers(150, "USER");
		List<Callable<String>> attempts = new ArrayList<>();
		for (int round = 0; round < 3; round++) {
			users.forEach(user -> attempts.add(reserve(event, user)));
		}

		Result result = run(150, attempts);

		assertCompleted(result);
		assertThat(result.count(SUCCESS)).isEqualTo(100);
		assertThat(result.outcomes().keySet()).containsOnly(SUCCESS, "EVENT_FULL", "ALREADY_RESERVED");
		assertThat(result.count("EVENT_FULL") + result.count("ALREADY_RESERVED")).isEqualTo(350);
		EventState state = assertConsistent(event, 100);
		assertThat(state.distinctConfirmedUsers()).isEqualTo(100);
	}

	@Test
	void simultaneousCancelsReleaseSeatExactlyOnce() throws Exception {
		UUID event = publishedEvent(10);
		UUID user = data.insertUser("USER");
		ReservationResponse reservation = reservationService.reserve(event, user);
		List<Callable<String>> attempts = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			attempts.add(cancel(reservation.id(), user));
		}

		Result result = run(20, attempts);

		assertCompleted(result);
		assertThat(result.count(CANCELLED)).isEqualTo(20);
		EventState state = data.state(event);
		assertThat(state.reservedCount()).isZero();
		assertThat(state.confirmed()).isZero();
		assertThat(state.cancelled()).isEqualTo(1);
	}

	/**
	 * Holders cancel while others reserve, and some holders re-reserve at the same moment as their
	 * own cancellation: the interleaving that would deadlock without the event-row-first lock order.
	 */
	@RepeatedTest(20)
	void cancelAndReserveChurnStaysConsistentWithoutDeadlock() throws Exception {
		UUID event = publishedEvent(10);
		List<UUID> holders = data.insertUsers(10, "USER");
		List<UUID> newcomers = data.insertUsers(50, "USER");
		List<ReservationResponse> held = holders.stream().map(user -> reservationService.reserve(event, user))
				.toList();

		List<Callable<String>> attempts = new ArrayList<>();
		for (int i = 0; i < holders.size(); i++) {
			attempts.add(cancel(held.get(i).id(), holders.get(i)));
			if (i % 2 == 0) {
				attempts.add(reserve(event, holders.get(i)));
			}
		}
		newcomers.forEach(user -> attempts.add(reserve(event, user)));

		Result result = run(attempts.size(), attempts);

		assertCompleted(result);
		assertThat(result.count(CANCELLED)).isEqualTo(10);
		assertThat(result.outcomes().keySet()).isSubsetOf(SUCCESS, CANCELLED, "EVENT_FULL", "ALREADY_RESERVED");
		EventState state = assertConsistent(event, (int) result.count(SUCCESS));
		assertThat(state.confirmed()).isLessThanOrEqualTo(10);
		assertThat(state.cancelled()).isEqualTo(10);
	}

	private Callable<String> reserve(UUID event, UUID user) {
		return () -> {
			try {
				reservationService.reserve(event, user);
				return SUCCESS;
			}
			catch (ApiException ex) {
				return ex.getCode();
			}
		};
	}

	private Callable<String> cancel(UUID reservation, UUID user) {
		return () -> reservationService.cancel(reservation, user).status().name();
	}

	private Result run(int workers, List<Callable<String>> attempts) throws InterruptedException {
		return ConcurrentAttempts.run(workers, attempts,
				() -> hikari().getHikariPoolMXBean().getThreadsAwaitingConnection());
	}

	private HikariDataSource hikari() {
		return (HikariDataSource) dataSource;
	}

	private UUID publishedEvent(int capacity) {
		return data.insertEvent(organizer, "PUBLISHED", capacity, OPEN, STARTS);
	}

	private long countReservations(UUID event) {
		return jdbcTemplate.queryForObject("select count(*) from reservations where event_id = ?", Long.class, event);
	}

	private static void assertCompleted(Result result) {
		assertThat(result.finished()).as("all attempts finished before the timeout").isTrue();
		assertThat(result.unexpected()).as("unexpected exceptions").isEmpty();
	}

	/** reserved_count equals CONFIRMED rows, equals the expected count, and nobody holds two. */
	private EventState assertConsistent(UUID event, int expectedConfirmed) {
		EventState state = data.state(event);
		assertThat(state.confirmed()).as("CONFIRMED rows").isEqualTo(expectedConfirmed);
		assertThat(state.reservedCount()).as("reserved_count").isEqualTo(expectedConfirmed);
		assertThat(state.reservedCount()).as("reserved_count within capacity").isLessThanOrEqualTo(state.capacity());
		assertThat(state.maxConfirmedPerUser()).as("max CONFIRMED per user").isLessThanOrEqualTo(1);
		return state;
	}

}
