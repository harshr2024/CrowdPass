package com.crowdpass.event;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

	Page<Event> findByStatusAndEndsAtAfter(EventStatus status, Instant endsAfter, Pageable pageable);

	Optional<Event> findByIdAndStatus(UUID id, EventStatus status);

	Page<Event> findByOrganizerId(UUID organizerId, Pageable pageable);

	// ---- Capacity operations: used only by the reservation module, inside its transactions. ----

	/**
	 * Atomically claims one seat if the event is published, registration is open at {@code now},
	 * and a seat is free. Returns 1 if a seat was claimed (the event row is then locked until the
	 * transaction ends), otherwise 0.
	 *
	 * <p>Correctness relies on READ COMMITTED: a transaction that waits for the row lock re-evaluates
	 * the whole WHERE clause against the newly committed row before updating it.
	 */
	@Modifying
	@Query(nativeQuery = true, value = """
			UPDATE events
			SET    reserved_count = reserved_count + 1
			WHERE  id = :eventId
			  AND  status = 'PUBLISHED'
			  AND  registration_open_at <= :now
			  AND  registration_close_at > :now
			  AND  reserved_count < capacity
			""")
	int tryAcquireSeat(@Param("eventId") UUID eventId, @Param("now") Instant now);

	/** Releases one seat. Returns 0 only if the counter has already drifted to zero. */
	@Modifying
	@Query(nativeQuery = true, value = """
			UPDATE events
			SET    reserved_count = reserved_count - 1
			WHERE  id = :eventId
			  AND  reserved_count > 0
			""")
	int releaseSeat(@Param("eventId") UUID eventId);

	/**
	 * Locks the event row for a seat or waitlist change. Callers must take this lock before
	 * modifying any reservation or waitlist entry of the event (event-row-first lock order).
	 */
	@Query(nativeQuery = true, value = """
			SELECT status, capacity, reserved_count AS reservedCount,
			       registration_open_at AS registrationOpenAt, registration_close_at AS registrationCloseAt,
			       starts_at AS startsAt
			FROM   events
			WHERE  id = :eventId
			FOR UPDATE
			""")
	Optional<LockedEvent> lockForSeatChange(@Param("eventId") UUID eventId);

}
