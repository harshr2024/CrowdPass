package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Verifies the invariants that V3 enforces at the database level. Each test is rolled back. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class WaitlistSchemaIntegrationTest {

	private static final OffsetDateTime T0 = OffsetDateTime.parse("2030-01-01T00:00:00Z");
	private static final OffsetDateTime LATER = T0.plusDays(1);

	@Autowired
	private NamedParameterJdbcTemplate jdbc;

	private UUID eventId;
	private UUID otherEventId;
	private UUID userId;
	private UUID otherUserId;
	private UUID reservationId;

	@BeforeEach
	void setUp() {
		UUID organizer = insert("users", user());
		userId = insert("users", user());
		otherUserId = insert("users", user());
		eventId = insert("events", event(organizer));
		otherEventId = insert("events", event(organizer));
		reservationId = insert("reservations", reservation(eventId, userId));
	}

	static Stream<Arguments> rowLevelViolations() {
		return Stream.of(
				// An unknown status also fails ck_waitlist_entries_state_fields, which PostgreSQL checks first
				// (CHECK constraints are evaluated in name order), so ck_waitlist_entries_status is never reported.
				violation("unknown status", Map.of("status", "EXPIRED"), "ck_waitlist_entries_state_fields"),
				violation("WAITING with promoted_at", Map.of("promoted_at", LATER), "ck_waitlist_entries_state_fields"),
				violation("WAITING with left_at", Map.of("left_at", LATER), "ck_waitlist_entries_state_fields"),
				violation("WAITING with reservation", Map.of("reservation_id", "$reservation"),
						"ck_waitlist_entries_state_fields"),
				violation("PROMOTED without reservation", Map.of("status", "PROMOTED", "promoted_at", LATER),
						"ck_waitlist_entries_state_fields"),
				violation("PROMOTED without promoted_at", Map.of("status", "PROMOTED", "reservation_id", "$reservation"),
						"ck_waitlist_entries_state_fields"),
				violation("PROMOTED with left_at", Map.of("status", "PROMOTED", "reservation_id", "$reservation",
						"promoted_at", LATER, "left_at", LATER), "ck_waitlist_entries_state_fields"),
				violation("LEFT without left_at", Map.of("status", "LEFT"), "ck_waitlist_entries_state_fields"),
				violation("LEFT with promoted_at", Map.of("status", "LEFT", "left_at", LATER, "promoted_at", LATER),
						"ck_waitlist_entries_state_fields"),
				violation("promoted before created", Map.of("status", "PROMOTED", "reservation_id", "$reservation",
						"promoted_at", T0.minusSeconds(1)), "ck_waitlist_entries_promoted_after_created"),
				violation("left before created", Map.of("status", "LEFT", "left_at", T0.minusSeconds(1)),
						"ck_waitlist_entries_left_after_created"),
				violation("nonexistent event", Map.of("event_id", UUID.randomUUID()), "fk_waitlist_entries_event"),
				violation("nonexistent user", Map.of("user_id", UUID.randomUUID()), "fk_waitlist_entries_user"),
				violation("promoted reservation of another user",
						Map.of("user_id", "$otherUser", "status", "PROMOTED", "reservation_id", "$reservation",
								"promoted_at", LATER), "fk_waitlist_entries_reservation"),
				violation("promoted reservation of another event",
						Map.of("event_id", "$otherEvent", "status", "PROMOTED", "reservation_id", "$reservation",
								"promoted_at", LATER), "fk_waitlist_entries_reservation"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rowLevelViolations")
	void rejectsInvalidEntry(String description, Map<String, Object> overrides, String expectedConstraint) {
		Map<String, Object> row = entry(eventId, userId);
		overrides.forEach((column, value) -> row.put(column, resolve(value)));

		assertViolates(expectedConstraint, () -> insert("waitlist_entries", row));
	}

	@Test
	void acceptsEachValidStateAndAllowsHistoryForSameUser() {
		Map<String, Object> left = entry(eventId, userId);
		left.putAll(Map.of("status", "LEFT", "left_at", LATER));
		Map<String, Object> promoted = entry(eventId, userId);
		promoted.putAll(Map.of("status", "PROMOTED", "reservation_id", reservationId, "promoted_at", LATER));
		insert("waitlist_entries", left);
		insert("waitlist_entries", promoted);
		insert("waitlist_entries", entry(eventId, userId));

		assertThat(count("select count(*) from waitlist_entries where user_id = :id", userId)).isEqualTo(3);
	}

	@Test
	void queueSeqIsDatabaseAssignedAndIncreasing() {
		UUID first = insert("waitlist_entries", entry(eventId, userId));
		UUID second = insert("waitlist_entries", entry(eventId, otherUserId));

		Long firstSeq = seq(first);
		Long secondSeq = seq(second);
		assertThat(firstSeq).isNotNull();
		assertThat(secondSeq).isGreaterThan(firstSeq);
	}

	@Test
	void queueSeqCannotBeSuppliedByApplication() {
		Map<String, Object> row = entry(eventId, userId);
		row.put("queue_seq", 1L);

		assertThatThrownBy(() -> insert("waitlist_entries", row))
				.isInstanceOf(DataAccessException.class)
				.satisfies(ex -> assertThat(serverError(ex).getSQLState()).isEqualTo("428C9"));
	}

	@Test
	void queueSeqUniquenessIsEnforcedExplicitly() {
		UUID first = insert("waitlist_entries", entry(eventId, userId));
		Map<String, Object> row = entry(eventId, otherUserId);

		assertViolates("uq_waitlist_entries_queue_seq", () -> jdbc.update("""
				insert into waitlist_entries (id, event_id, user_id, queue_seq, status, created_at)
				overriding system value
				values (:id, :event_id, :user_id, :seq, 'WAITING', :created_at)
				""", Map.of("id", row.get("id"), "event_id", eventId, "user_id", otherUserId, "seq", seq(first),
				"created_at", T0)));
	}

	@Test
	void rejectsSecondWaitingEntryForSameUserAndEvent() {
		insert("waitlist_entries", entry(eventId, userId));

		assertViolates("ux_waitlist_entries_waiting_user_event", () -> insert("waitlist_entries", entry(eventId, userId)));
	}

	@Test
	void rejectsReservationFulfillingTwoEntries() {
		Map<String, Object> first = entry(eventId, userId);
		first.putAll(Map.of("status", "PROMOTED", "reservation_id", reservationId, "promoted_at", LATER));
		insert("waitlist_entries", first);
		Map<String, Object> second = entry(eventId, userId);
		second.putAll(Map.of("status", "PROMOTED", "reservation_id", reservationId, "promoted_at", LATER));

		assertViolates("ux_waitlist_entries_promoted_reservation", () -> insert("waitlist_entries", second));
	}

	static Stream<Arguments> restrictedDeletes() {
		return Stream.of(
				Arguments.of("delete from reservations where id = :reservationId", "fk_waitlist_entries_reservation"),
				Arguments.of("delete from events where id = :otherEventId", "fk_waitlist_entries_event"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("restrictedDeletes")
	void referencedRowsCannotBeDeleted(String sql, String expectedConstraint) {
		Map<String, Object> promoted = entry(eventId, userId);
		promoted.putAll(Map.of("status", "PROMOTED", "reservation_id", reservationId, "promoted_at", LATER));
		insert("waitlist_entries", promoted);
		insert("waitlist_entries", entry(otherEventId, otherUserId));

		assertViolates(expectedConstraint,
				() -> jdbc.update(sql, Map.of("reservationId", reservationId, "otherEventId", otherEventId)));
	}

	@Test
	void expectedIndexesExist() {
		List<String> indexes = jdbc.queryForList(
				"select indexname from pg_indexes where tablename in ('waitlist_entries', 'reservations')",
				Map.of(), String.class);

		assertThat(indexes).contains("pk_waitlist_entries", "uq_waitlist_entries_queue_seq",
				"ux_waitlist_entries_promoted_reservation", "ux_waitlist_entries_waiting_user_event",
				"ix_waitlist_entries_waiting_order", "ix_waitlist_entries_user_event_seq",
				"uq_reservations_id_event_user");
	}

	private Object resolve(Object value) {
		return switch (value instanceof String s ? s : "") {
			case "$reservation" -> reservationId;
			case "$otherUser" -> otherUserId;
			case "$otherEvent" -> otherEventId;
			default -> value;
		};
	}

	private static Arguments violation(String description, Map<String, Object> overrides, String constraint) {
		return Arguments.of(description, overrides, constraint);
	}

	private static Map<String, Object> user() {
		UUID id = UUID.randomUUID();
		Map<String, Object> row = new HashMap<>();
		row.putAll(Map.of("id", id, "email", "u-" + id + "@example.com", "password_hash", "x", "display_name", "U",
				"role", "USER", "created_at", T0, "updated_at", T0));
		return row;
	}

	private static Map<String, Object> event(UUID organizer) {
		Map<String, Object> row = new HashMap<>();
		row.putAll(Map.of("id", UUID.randomUUID(), "organizer_id", organizer, "name", "E", "capacity", 10,
				"status", "PUBLISHED", "time_zone", "UTC", "registration_open_at", T0,
				"registration_close_at", T0.plusDays(5), "starts_at", T0.plusDays(5), "ends_at", T0.plusDays(6)));
		row.put("created_at", T0);
		row.put("updated_at", T0);
		return row;
	}

	private static Map<String, Object> reservation(UUID event, UUID user) {
		Map<String, Object> row = new HashMap<>();
		row.putAll(Map.of("id", UUID.randomUUID(), "event_id", event, "user_id", user, "status", "CONFIRMED",
				"created_at", T0));
		return row;
	}

	private static Map<String, Object> entry(UUID event, UUID user) {
		Map<String, Object> row = new HashMap<>();
		row.putAll(Map.of("id", UUID.randomUUID(), "event_id", event, "user_id", user, "status", "WAITING",
				"created_at", T0));
		return row;
	}

	private UUID insert(String table, Map<String, Object> row) {
		String columns = String.join(", ", row.keySet());
		String values = ":" + String.join(", :", row.keySet());
		jdbc.update("insert into " + table + " (" + columns + ") values (" + values + ")", row);
		return (UUID) row.get("id");
	}

	private Long seq(UUID entryId) {
		return jdbc.queryForObject("select queue_seq from waitlist_entries where id = :id", Map.of("id", entryId),
				Long.class);
	}

	private long count(String sql, UUID id) {
		return jdbc.queryForObject(sql, Map.of("id", id), Long.class);
	}

	private static void assertViolates(String constraint, Executable action) {
		assertThatThrownBy(action::execute)
				.isInstanceOf(DataAccessException.class)
				.satisfies(ex -> assertThat(serverError(ex).getConstraint()).isEqualTo(constraint));
	}

	private static ServerErrorMessage serverError(Throwable ex) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
		assertThat(cause).isInstanceOf(PSQLException.class);
		return ((PSQLException) cause).getServerErrorMessage();
	}

}
