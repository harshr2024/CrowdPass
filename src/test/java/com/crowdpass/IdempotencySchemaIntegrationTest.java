package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.crowdpass.idempotency.IdempotencyCleanupService;

/** Database constraints and cleanup scope introduced by V5. */
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@SpringBootTest(properties = {
		"crowdpass.idempotency.cleanup.fixed-delay=24h",
		"crowdpass.idempotency.cleanup.batch-size=2" })
class IdempotencySchemaIntegrationTest {

	private static final Instant NOW = MutableClock.Config.START;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MutableClock clock;

	@Autowired
	private IdempotencyCleanupService cleanupService;

	private ReservationTestData data;
	private UUID user;

	@BeforeEach
	void setUp() {
		data = new ReservationTestData(jdbc);
		data.reset();
		clock.set(NOW);
		user = data.insertUser("USER");
	}

	@Test
	void acceptsValidInProgressAndCompletedRows() {
		insertInProgress(user, hash(1), hash(2));
		insertCompleted(user, hash(3), hash(4), NOW.minusSeconds(1));

		assertThat(count()).isEqualTo(2);
	}

	@Test
	void hashesMustBeExactlyThirtyTwoBytes() {
		assertConstraint("ck_idempotency_records_key_hash",
				() -> insertInProgress(user, new byte[31], hash(2)));
		assertConstraint("ck_idempotency_records_fingerprint",
				() -> insertInProgress(user, hash(1), new byte[33]));
	}

	@Test
	void completedResponseFieldsAreAllOrNothing() {
		assertConstraint("ck_idempotency_records_state_response", () -> jdbc.update("""
				insert into idempotency_records
				    (id, user_id, key_hash, request_fingerprint, state, created_at, response_status)
				values (?, ?, ?, ?, 'COMPLETED', ?, 201)
				""", UUID.randomUUID(), user, hash(1), hash(2), utc(NOW)));
		assertConstraint("ck_idempotency_records_state_response", () -> jdbc.update("""
				insert into idempotency_records
				    (id, user_id, key_hash, request_fingerprint, state, created_at, completed_at,
				     expires_at, response_status, response_body, response_location)
				values (?, ?, ?, ?, 'COMPLETED', ?, ?, ?, 500, cast('{}' as jsonb), '/x')
				""", UUID.randomUUID(), user, hash(3), hash(4), utc(NOW), utc(NOW), utc(NOW.plusSeconds(1))));
	}

	@Test
	void keyUniquenessIsScopedByUser() {
		byte[] key = hash(7);
		insertInProgress(user, key, hash(1));
		assertConstraint("uq_idempotency_records_user_key", () -> insertInProgress(user, key, hash(2)));

		UUID other = data.insertUser("USER");
		insertInProgress(other, key, hash(3));
		assertThat(count()).isEqualTo(2);
	}

	@Test
	void unknownUserIsRejectedAndDeletionIsRestricted() {
		assertConstraint("fk_idempotency_records_user",
				() -> insertInProgress(UUID.randomUUID(), hash(1), hash(2)));
		insertInProgress(user, hash(3), hash(4));
		assertConstraint("fk_idempotency_records_user", () -> jdbc.update("delete from users where id = ?", user));
	}

	@Test
	void cleanupIsBoundedAndNeverDeletesInProgress() {
		insertInProgress(user, hash(1), hash(2));
		insertCompleted(user, hash(3), hash(4), NOW.minusSeconds(3));
		insertCompleted(user, hash(5), hash(6), NOW.minusSeconds(2));
		insertCompleted(user, hash(7), hash(8), NOW.minusSeconds(1));

		assertThat(cleanupService.cleanupOnce()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from idempotency_records where state = 'IN_PROGRESS'",
				Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from idempotency_records where state = 'COMPLETED'",
				Long.class)).isEqualTo(1);
	}

	@Test
	void cleanupIndexExistsAndIsPartial() {
		String definition = jdbc.queryForObject("""
				select indexdef from pg_indexes
				where tablename = 'idempotency_records'
				  and indexname = 'ix_idempotency_records_completed_expiry'
				""", String.class);

		assertThat(definition).contains("(expires_at, id)").contains("WHERE ((state)::text = 'COMPLETED'::text)");
	}

	private void insertInProgress(UUID owner, byte[] key, byte[] fingerprint) {
		jdbc.update("""
				insert into idempotency_records
				    (id, user_id, key_hash, request_fingerprint, state, created_at)
				values (?, ?, ?, ?, 'IN_PROGRESS', ?)
				""", UUID.randomUUID(), owner, key, fingerprint, utc(NOW));
	}

	private void insertCompleted(UUID owner, byte[] key, byte[] fingerprint, Instant expiresAt) {
		jdbc.update("""
				insert into idempotency_records
				    (id, user_id, key_hash, request_fingerprint, state, response_status, response_body,
				     response_location, created_at, completed_at, expires_at)
				values (?, ?, ?, ?, 'COMPLETED', 201, cast('{}' as jsonb), '/api/reservations/x', ?, ?, ?)
				""", UUID.randomUUID(), owner, key, fingerprint, utc(NOW.minusSeconds(10)), utc(NOW.minusSeconds(9)),
				utc(expiresAt));
	}

	private long count() {
		return jdbc.queryForObject("select count(*) from idempotency_records", Long.class);
	}

	private static byte[] hash(int marker) {
		byte[] value = new byte[32];
		Arrays.fill(value, (byte) marker);
		return value;
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static void assertConstraint(String expected, Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(DataIntegrityViolationException.class).satisfies(ex -> {
			Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
			assertThat(cause).isInstanceOf(org.postgresql.util.PSQLException.class);
			assertThat(((org.postgresql.util.PSQLException) cause).getServerErrorMessage().getConstraint())
					.isEqualTo(expected);
		});
	}

}
