package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the invariants that V2 enforces at the database level, independent of any
 * application code. Each test runs in a transaction that is rolled back afterwards.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class CoreSchemaIntegrationTest {

	private static final OffsetDateTime T0 = OffsetDateTime.parse("2030-01-01T00:00:00Z");
	private static final OffsetDateTime REGISTRATION_OPEN = T0.plusDays(1);
	private static final OffsetDateTime STARTS = T0.plusDays(10);
	// Registration closing exactly at the start time is the allowed boundary.
	private static final OffsetDateTime REGISTRATION_CLOSE = STARTS;
	private static final OffsetDateTime ENDS = STARTS.plusHours(3);

	@Autowired
	private NamedParameterJdbcTemplate jdbc;

	static Stream<Arguments> rowLevelViolations() {
		return Stream.of(
				violation("user email with uppercase", "users",
						Map.of("email", "Alice@Example.com"), "ck_users_email_canonical"),
				violation("user email with surrounding whitespace", "users",
						Map.of("email", " alice@example.com "), "ck_users_email_canonical"),
				violation("user blank display name", "users",
						Map.of("display_name", "   "), "ck_users_display_name_not_blank"),
				violation("user unknown role", "users",
						Map.of("role", "SUPERUSER"), "ck_users_role"),
				violation("user updated before created", "users",
						Map.of("updated_at", T0.minusSeconds(1)), "ck_users_updated_after_created"),

				violation("event with nonexistent organizer", "events",
						Map.of("organizer_id", UUID.randomUUID()), "fk_events_organizer"),
				violation("event blank name", "events",
						Map.of("name", " "), "ck_events_name_not_blank"),
				violation("event zero capacity", "events",
						Map.of("capacity", 0), "ck_events_capacity_positive"),
				violation("event negative reserved count", "events",
						Map.of("reserved_count", -1), "ck_events_reserved_count_within_capacity"),
				violation("event reserved count above capacity", "events",
						Map.of("capacity", 10, "reserved_count", 11), "ck_events_reserved_count_within_capacity"),
				violation("event unknown status", "events",
						Map.of("status", "OPEN"), "ck_events_status"),
				violation("event cancelled without cancelled_at", "events",
						Map.of("status", "CANCELLED"), "ck_events_cancelled_at_matches_status"),
				violation("event cancelled_at while published", "events",
						Map.of("cancelled_at", T0.plusDays(2)), "ck_events_cancelled_at_matches_status"),
				violation("event registration opens when it closes", "events",
						Map.of("registration_open_at", REGISTRATION_CLOSE), "ck_events_registration_window"),
				violation("event registration closes after start", "events",
						Map.of("registration_close_at", STARTS.plusSeconds(1)),
						"ck_events_registration_closes_before_start"),
				violation("event ends when it starts", "events",
						Map.of("ends_at", STARTS), "ck_events_time_order"),
				violation("event updated before created", "events",
						Map.of("updated_at", T0.minusSeconds(1)), "ck_events_updated_after_created"),
				violation("event cancelled before created", "events",
						Map.of("status", "CANCELLED", "cancelled_at", T0.minusSeconds(1)),
						"ck_events_cancelled_after_created"),

				violation("reservation for nonexistent event", "reservations",
						Map.of("event_id", UUID.randomUUID()), "fk_reservations_event"),
				violation("reservation for nonexistent user", "reservations",
						Map.of("user_id", UUID.randomUUID()), "fk_reservations_user"),
				violation("reservation unknown status", "reservations",
						Map.of("status", "PENDING"), "ck_reservations_status"),
				violation("reservation cancelled without cancelled_at", "reservations",
						Map.of("status", "CANCELLED"), "ck_reservations_cancelled_at_matches_status"),
				violation("reservation confirmed with cancelled_at", "reservations",
						Map.of("cancelled_at", T0.plusDays(3)), "ck_reservations_cancelled_at_matches_status"),
				violation("reservation cancelled before created", "reservations",
						Map.of("status", "CANCELLED", "cancelled_at", T0.plusDays(2).minusSeconds(1)),
						"ck_reservations_cancelled_after_created"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rowLevelViolations")
	void rejectsRowViolatingConstraint(String description, String table, Map<String, Object> overrides,
			String expectedConstraint) {
		Map<String, Object> row = switch (table) {
			case "users" -> userRow();
			case "events" -> eventRow(insert("users", userRow()));
			case "reservations" -> {
				UUID eventId = insert("events", eventRow(insert("users", userRow())));
				yield reservationRow(eventId, insert("users", userRow()));
			}
			default -> throw new IllegalArgumentException("Unknown table: " + table);
		};
		row.putAll(overrides);

		assertViolates(expectedConstraint, () -> insert(table, row));
	}

	@Test
	void acceptsValidRowsIncludingRegistrationClosingAtStart() {
		UUID eventId = insert("events", eventRow(insert("users", userRow())));
		insert("reservations", reservationRow(eventId, insert("users", userRow())));

		assertThat(count("select count(*) from reservations where event_id = :id", eventId)).isEqualTo(1);
	}

	@Test
	void reservedCountDefaultsToZero() {
		UUID eventId = insert("events", eventRow(insert("users", userRow())));

		assertThat(reservedCount(eventId)).isZero();
	}

	@Test
	void timestampsHaveNoDatabaseDefault() {
		Map<String, Object> user = userRow();
		user.remove("created_at");

		assertThatThrownBy(() -> insert("users", user))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> {
					ServerErrorMessage error = serverError(ex);
					assertThat(error.getSQLState()).isEqualTo("23502");
					assertThat(error.getColumn()).isEqualTo("created_at");
				});
	}

	@Test
	void rejectsDuplicateEmail() {
		insert("users", userRow("dup@example.com"));

		assertViolates("uq_users_email", () -> insert("users", userRow("dup@example.com")));
	}

	@Test
	void rejectsSecondConfirmedReservationForSameUserAndEvent() {
		UUID eventId = insert("events", eventRow(insert("users", userRow())));
		UUID userId = insert("users", userRow());
		insert("reservations", reservationRow(eventId, userId));

		assertViolates("ux_reservations_active_user_event",
				() -> insert("reservations", reservationRow(eventId, userId)));
	}

	@Test
	void cancelledHistoryDoesNotBlockNewConfirmedReservation() {
		UUID eventId = insert("events", eventRow(insert("users", userRow())));
		UUID userId = insert("users", userRow());
		insert("reservations", cancelledReservationRow(eventId, userId));
		insert("reservations", cancelledReservationRow(eventId, userId));
		insert("reservations", reservationRow(eventId, userId));

		assertThat(count("select count(*) from reservations where user_id = :id", userId)).isEqualTo(3);
	}

	@Test
	void conditionalIncrementStopsAtCapacity() {
		Map<String, Object> event = eventRow(insert("users", userRow()));
		event.put("capacity", 2);
		UUID eventId = insert("events", event);

		List<Integer> rowsUpdated = Stream.generate(() -> jdbc.update("""
				update events set reserved_count = reserved_count + 1
				where id = :id and reserved_count < capacity
				""", Map.of("id", eventId))).limit(3).toList();

		assertThat(rowsUpdated).containsExactly(1, 1, 0);
		assertThat(reservedCount(eventId)).isEqualTo(2);
	}

	@Test
	void rejectsUnguardedIncrementBeyondCapacity() {
		Map<String, Object> event = eventRow(insert("users", userRow()));
		event.put("capacity", 1);
		event.put("reserved_count", 1);
		UUID eventId = insert("events", event);

		assertViolates("ck_events_reserved_count_within_capacity", () -> jdbc.update(
				"update events set reserved_count = reserved_count + 1 where id = :id", Map.of("id", eventId)));
	}

	@Test
	void rejectsCapacityReductionBelowReservedCount() {
		Map<String, Object> event = eventRow(insert("users", userRow()));
		event.put("capacity", 5);
		event.put("reserved_count", 3);
		UUID eventId = insert("events", event);

		assertViolates("ck_events_reserved_count_within_capacity", () -> jdbc.update(
				"update events set capacity = 2 where id = :id", Map.of("id", eventId)));
	}

	static Stream<Arguments> restrictedDeletes() {
		return Stream.of(
				Arguments.of("delete from users where id = :organizerId", "fk_events_organizer"),
				Arguments.of("delete from users where id = :attendeeId", "fk_reservations_user"),
				Arguments.of("delete from events where id = :eventId", "fk_reservations_event"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("restrictedDeletes")
	void historyBearingRowsCannotBeDeleted(String deleteSql, String expectedConstraint) {
		UUID organizerId = insert("users", userRow());
		UUID eventId = insert("events", eventRow(organizerId));
		UUID attendeeId = insert("users", userRow());
		insert("reservations", reservationRow(eventId, attendeeId));

		assertViolates(expectedConstraint, () -> jdbc.update(deleteSql,
				Map.of("organizerId", organizerId, "attendeeId", attendeeId, "eventId", eventId)));
	}

	@Test
	void expectedIndexesExistAndActiveReservationIndexIsPartial() {
		Map<String, String> indexes = new HashMap<>();
		jdbc.query("""
				select indexname, indexdef from pg_indexes
				where schemaname = 'public' and tablename in ('users', 'events', 'reservations')
				""", rs -> {
			indexes.put(rs.getString("indexname"), rs.getString("indexdef"));
		});

		assertThat(indexes).containsKeys("pk_users", "uq_users_email", "pk_events", "ix_events_organizer_id",
				"pk_reservations", "ux_reservations_active_user_event", "ix_reservations_user_created_at");
		assertThat(indexes.get("ux_reservations_active_user_event"))
				.contains("UNIQUE")
				.contains("(event_id, user_id)")
				.contains("WHERE ((status)::text = 'CONFIRMED'::text)");
	}

	private static Arguments violation(String description, String table, Map<String, Object> overrides,
			String expectedConstraint) {
		return Arguments.of(description, table, overrides, expectedConstraint);
	}

	private static Map<String, Object> userRow() {
		return userRow("user-" + UUID.randomUUID() + "@example.com");
	}

	private static Map<String, Object> userRow(String email) {
		Map<String, Object> row = new HashMap<>();
		row.put("id", UUID.randomUUID());
		row.put("email", email);
		row.put("password_hash", "placeholder-hash");
		row.put("display_name", "Test User");
		row.put("role", "USER");
		row.put("created_at", T0);
		row.put("updated_at", T0);
		return row;
	}

	private static Map<String, Object> eventRow(UUID organizerId) {
		Map<String, Object> row = new HashMap<>();
		row.put("id", UUID.randomUUID());
		row.put("organizer_id", organizerId);
		row.put("name", "Test Event");
		row.put("capacity", 100);
		row.put("status", "PUBLISHED");
		row.put("time_zone", "America/Los_Angeles");
		row.put("registration_open_at", REGISTRATION_OPEN);
		row.put("registration_close_at", REGISTRATION_CLOSE);
		row.put("starts_at", STARTS);
		row.put("ends_at", ENDS);
		row.put("created_at", T0);
		row.put("updated_at", T0);
		return row;
	}

	private static Map<String, Object> reservationRow(UUID eventId, UUID userId) {
		Map<String, Object> row = new HashMap<>();
		row.put("id", UUID.randomUUID());
		row.put("event_id", eventId);
		row.put("user_id", userId);
		row.put("status", "CONFIRMED");
		row.put("created_at", T0.plusDays(2));
		return row;
	}

	private static Map<String, Object> cancelledReservationRow(UUID eventId, UUID userId) {
		Map<String, Object> row = reservationRow(eventId, userId);
		row.put("status", "CANCELLED");
		row.put("cancelled_at", T0.plusDays(3));
		return row;
	}

	private UUID insert(String table, Map<String, Object> row) {
		String columns = String.join(", ", row.keySet());
		String values = ":" + String.join(", :", row.keySet());
		jdbc.update("insert into " + table + " (" + columns + ") values (" + values + ")", row);
		return (UUID) row.get("id");
	}

	private int reservedCount(UUID eventId) {
		return count("select reserved_count from events where id = :id", eventId);
	}

	private int count(String sql, UUID id) {
		return jdbc.queryForObject(sql, Map.of("id", id), Integer.class);
	}

	private static void assertViolates(String expectedConstraint, Executable action) {
		assertThatThrownBy(action::execute)
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> assertThat(serverError(ex).getConstraint()).isEqualTo(expectedConstraint));
	}

	private static ServerErrorMessage serverError(Throwable ex) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
		assertThat(cause).isInstanceOf(PSQLException.class);
		return ((PSQLException) cause).getServerErrorMessage();
	}

}
