package com.crowdpass;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC fixtures and database-state queries shared by reservation tests. */
public final class ReservationTestData {

	/** Persisted counts for one event, read after all concurrent work has finished. */
	public record EventState(int capacity, int reservedCount, long confirmed, long cancelled,
			long distinctConfirmedUsers, long maxConfirmedPerUser) {
	}

	private final JdbcTemplate jdbc;

	public ReservationTestData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void reset() {
		jdbc.execute("truncate reservations, events, users");
	}

	public List<UUID> insertUsers(int count, String role) {
		List<UUID> ids = new ArrayList<>(count);
		List<Object[]> rows = new ArrayList<>(count);
		OffsetDateTime createdAt = utc(Instant.parse("2026-01-01T00:00:00Z"));
		for (int i = 0; i < count; i++) {
			UUID id = UUID.randomUUID();
			ids.add(id);
			rows.add(new Object[] { id, "user-" + id + "@example.com", role, createdAt, createdAt });
		}
		jdbc.batchUpdate("""
				insert into users (id, email, password_hash, display_name, role, created_at, updated_at)
				values (?, ?, 'unused', 'Test User', ?, ?, ?)
				""", rows);
		return ids;
	}

	public UUID insertUser(String role) {
		return insertUsers(1, role).getFirst();
	}

	/** An event whose registration window is [openAt, startsAt) and which lasts three hours. */
	public UUID insertEvent(UUID organizerId, String status, int capacity, Instant openAt, Instant startsAt) {
		UUID id = UUID.randomUUID();
		OffsetDateTime createdAt = utc(Instant.parse("2026-01-01T00:00:00Z"));
		jdbc.update("""
				insert into events (id, organizer_id, name, capacity, status, time_zone, registration_open_at,
				    registration_close_at, starts_at, ends_at, cancelled_at, created_at, updated_at)
				values (?, ?, 'Event', ?, ?, 'UTC', ?, ?, ?, ?, ?, ?, ?)
				""", id, organizerId, capacity, status, utc(openAt), utc(startsAt), utc(startsAt),
				utc(startsAt.plus(3, ChronoUnit.HOURS)), status.equals("CANCELLED") ? createdAt : null, createdAt,
				createdAt);
		return id;
	}

	public EventState state(UUID eventId) {
		Map<String, Object> event = jdbc.queryForMap("select capacity, reserved_count from events where id = ?",
				eventId);
		Map<String, Object> counts = jdbc.queryForMap("""
				select count(*) filter (where status = 'CONFIRMED') as confirmed,
				       count(*) filter (where status = 'CANCELLED') as cancelled,
				       count(distinct user_id) filter (where status = 'CONFIRMED') as distinct_users
				from reservations where event_id = ?
				""", eventId);
		Long maxPerUser = jdbc.queryForObject("""
				select coalesce(max(n), 0) from (
				    select count(*) as n from reservations
				    where event_id = ? and status = 'CONFIRMED' group by user_id) per_user
				""", Long.class, eventId);
		return new EventState((Integer) event.get("capacity"), (Integer) event.get("reserved_count"),
				(Long) counts.get("confirmed"), (Long) counts.get("cancelled"), (Long) counts.get("distinct_users"),
				maxPerUser);
	}

	public void setReservedCount(UUID eventId, int reservedCount) {
		jdbc.update("update events set reserved_count = ? where id = ?", reservedCount, eventId);
	}

	public String reservationStatus(UUID reservationId) {
		return jdbc.queryForObject("select status from reservations where id = ?", String.class, reservationId);
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
