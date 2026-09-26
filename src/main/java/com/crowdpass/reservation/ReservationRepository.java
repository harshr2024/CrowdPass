package com.crowdpass.reservation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

	Optional<Reservation> findByIdAndUserId(UUID id, UUID userId);

	Optional<Reservation> findFirstByEventIdAndUserIdAndStatus(UUID eventId, UUID userId, ReservationStatus status);

	Page<Reservation> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);

	/** Used only to explain a failed reservation; the unique index is the authority. */
	boolean existsByEventIdAndUserIdAndStatus(UUID eventId, UUID userId, ReservationStatus status);

	/**
	 * CONFIRMED to CANCELLED; returns 0 if already cancelled. Clears the persistence context so a
	 * reservation loaded earlier is re-read with its new state.
	 */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE reservations
			SET    status = 'CANCELLED', cancelled_at = :now
			WHERE  id = :reservationId
			  AND  status = 'CONFIRMED'
			""")
	int cancelIfConfirmed(@Param("reservationId") UUID reservationId, @Param("now") Instant now);

}
