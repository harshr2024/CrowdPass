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

/** JDBC fixtures and database-state queries shared by reservation and waitlist tests. */
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
		jdbc.execute("truncate notifications, outbox_events, waitlist_entries, reservations, events, users");
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
		return insertEvent(organizerId, status, capacity, openAt, startsAt, startsAt);
	}

	/** An event whose registration window is [openAt, closeAt) and which lasts three hours. */
	public UUID insertEvent(UUID organizerId, String status, int capacity, Instant openAt, Instant closeAt,
			Instant startsAt) {
		UUID id = UUID.randomUUID();
		OffsetDateTime createdAt = utc(Instant.parse("2026-01-01T00:00:00Z"));
		jdbc.update("""
				insert into events (id, organizer_id, name, capacity, status, time_zone, registration_open_at,
				    registration_close_at, starts_at, ends_at, cancelled_at, created_at, updated_at)
				values (?, ?, 'Event', ?, ?, 'UTC', ?, ?, ?, ?, ?, ?, ?)
				""", id, organizerId, capacity, status, utc(openAt), utc(closeAt), utc(startsAt),
				utc(startsAt.plus(3, ChronoUnit.HOURS)), status.equals("CANCELLED") ? createdAt : null, createdAt,
				createdAt);
		return id;
	}

	public void cancelEvent(UUID eventId) {
		jdbc.update("update events set status = 'CANCELLED', cancelled_at = created_at where id = ?", eventId);
	}

	/** Users with WAITING entries for the event, in queue_seq order. */
	public List<UUID> waitingUsers(UUID eventId) {
		return jdbc.queryForList("""
				select user_id from waitlist_entries where event_id = ? and status = 'WAITING' order by queue_seq
				""", UUID.class, eventId);
	}

	/** Users with PROMOTED entries for the event, in queue_seq order. */
	public List<UUID> promotedUsers(UUID eventId) {
		return jdbc.queryForList("""
				select user_id from waitlist_entries where event_id = ? and status = 'PROMOTED' order by queue_seq
				""", UUID.class, eventId);
	}

	/** Status of the user's latest waitlist entry for the event, or null. */
	public String latestEntryStatus(UUID eventId, UUID userId) {
		List<String> statuses = jdbc.queryForList("""
				select status from waitlist_entries where event_id = ? and user_id = ?
				order by queue_seq desc limit 1
				""", String.class, eventId, userId);
		return statuses.isEmpty() ? null : statuses.getFirst();
	}

	/**
	 * Checks every seat and waitlist invariant for an event that is published and has not started.
	 * Returns a description of each violation; an empty list means consistent.
	 */
	public List<String> invariantViolations(UUID eventId) {
		List<String> violations = new ArrayList<>();
		EventState state = state(eventId);
		long waiting = count("select count(*) from waitlist_entries where event_id = ? and status = 'WAITING'",
				eventId);
		if (state.reservedCount() != state.confirmed()) {
			violations.add("reserved_count " + state.reservedCount() + " != CONFIRMED rows " + state.confirmed());
		}
		if (state.reservedCount() > state.capacity()) {
			violations.add("reserved_count exceeds capacity");
		}
		if (state.maxConfirmedPerUser() > 1) {
			violations.add("a user holds more than one CONFIRMED reservation");
		}
		if (waiting > 0 && state.reservedCount() < state.capacity()) {
			violations.add("I1: " + waiting + " WAITING while " + (state.capacity() - state.reservedCount())
					+ " seat(s) free");
		}
		long confirmedAndWaiting = count("""
				select count(*) from waitlist_entries w
				join reservations r on r.event_id = w.event_id and r.user_id = w.user_id and r.status = 'CONFIRMED'
				where w.event_id = ? and w.status = 'WAITING'
				""", eventId);
		if (confirmedAndWaiting > 0) {
			violations.add("I2: " + confirmedAndWaiting + " user(s) both CONFIRMED and WAITING");
		}
		long maxWaitingPerUser = count("""
				select coalesce(max(n), 0) from (select count(*) as n from waitlist_entries
				where event_id = ? and status = 'WAITING' group by user_id) per_user
				""", eventId);
		if (maxWaitingPerUser > 1) {
			violations.add("a user has more than one WAITING entry");
		}
		long badLinks = count("""
				select count(*) from waitlist_entries w
				left join reservations r on r.id = w.reservation_id and r.event_id = w.event_id and r.user_id = w.user_id
				where w.event_id = ? and w.status = 'PROMOTED' and r.id is null
				""", eventId);
		if (badLinks > 0) {
			violations.add(badLinks + " PROMOTED entr(ies) without a matching reservation");
		}
		long fifoBreaks = count("""
				select count(*) from waitlist_entries p
				join waitlist_entries w on w.event_id = p.event_id and w.status = 'WAITING' and w.queue_seq < p.queue_seq
				where p.event_id = ? and p.status = 'PROMOTED'
				""", eventId);
		if (fifoBreaks > 0) {
			violations.add("FIFO: a PROMOTED entry is behind a still-WAITING entry");
		}
		long promotedWithoutOneEvent = count("""
				select count(*) from waitlist_entries w
				where w.event_id = ? and w.status = 'PROMOTED'
				  and (
				    select count(*) from outbox_events o
				    where o.aggregate_type = 'waitlist_entry'
				      and o.aggregate_id = w.id
				      and o.event_type = 'WAITLIST_PROMOTED'
				      and o.data->>'userId' = w.user_id::text
				      and o.data->>'eventId' = w.event_id::text
				      and o.data->>'reservationId' = w.reservation_id::text
				      and o.data->>'waitlistEntryId' = w.id::text
				  ) <> 1
				""", eventId);
		if (promotedWithoutOneEvent > 0) {
			violations.add(promotedWithoutOneEvent + " PROMOTED entr(ies) without exactly one WAITLIST_PROMOTED outbox event");
		}
		return violations;
	}

	public long outboxEventCount() {
		return jdbc.queryForObject("select count(*) from outbox_events", Long.class);
	}

	private long count(String sql, UUID eventId) {
		return jdbc.queryForObject(sql, Long.class, eventId);
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
