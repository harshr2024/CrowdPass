package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
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
import com.crowdpass.reservation.ReservationService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Optional stress variant: one platform thread per attempt. Excluded from the default build; run
 * with {@code ./mvnw test -Dgroups=stress -DexcludedGroups=}. Not the primary correctness proof.
 */
@Tag("stress")
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest
class ReservationStressTest {

	private static final Logger log = LoggerFactory.getLogger(ReservationStressTest.class);

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Test
	void thousandThreadsThousandAttemptsHundredSeats() throws Exception {
		ReservationTestData data = new ReservationTestData(jdbcTemplate);
		data.reset();
		clock.set(MutableClock.Config.START);
		UUID event = data.insertEvent(data.insertUser("ORGANIZER"), "PUBLISHED", 100,
				MutableClock.Config.START.minus(Duration.ofHours(1)), MutableClock.Config.START.plus(Duration.ofDays(1)));
		List<UUID> users = data.insertUsers(1_000, "USER");
		HikariDataSource hikari = (HikariDataSource) dataSource;

		Result result = ConcurrentAttempts.run(1_000, users.stream().<java.util.concurrent.Callable<String>>map(
				user -> () -> {
					try {
						reservationService.reserve(event, user);
						return "SUCCESS";
					}
					catch (ApiException ex) {
						return ex.getCode();
					}
				}).toList(), () -> hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());

		log.info("stress: outcomes={} peakInFlight={} peakAwaitingConnection={} pool={}", result.outcomes(),
				result.peakInFlight(), result.peakAwaitingConnection(), hikari.getMaximumPoolSize());
		assertThat(result.finished()).isTrue();
		assertThat(result.unexpected()).isEmpty();
		assertThat(result.count("SUCCESS")).isEqualTo(100);
		assertThat(result.count("EVENT_FULL")).isEqualTo(900);
		EventState state = data.state(event);
		assertThat(state.confirmed()).isEqualTo(100);
		assertThat(state.reservedCount()).isEqualTo(100);
		assertThat(state.distinctConfirmedUsers()).isEqualTo(100);
	}

}
