package com.crowdpass.reservation.waitlist;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Write methods must only be called while the caller holds the event row lock (see
 * {@code ReservationService}).
 */
public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

	/** Head of the queue: the WAITING entry with the lowest queue_seq. */
	Optional<WaitlistEntry> findFirstByEventIdAndStatusOrderByQueueSeqAsc(UUID eventId, WaitlistStatus status);

	/** The user's most recent entry for the event, in any status. */
	Optional<WaitlistEntry> findFirstByEventIdAndUserIdOrderByQueueSeqDesc(UUID eventId, UUID userId);

	boolean existsByEventIdAndUserIdAndStatus(UUID eventId, UUID userId, WaitlistStatus status);

	/** Number of WAITING entries ahead of {@code queueSeq}; a snapshot. */
	@Query("""
			select count(w) from WaitlistEntry w
			where w.event.id = :eventId
			  and w.status = com.crowdpass.reservation.waitlist.WaitlistStatus.WAITING
			  and w.queueSeq < :queueSeq
			""")
	long countWaitingAhead(@Param("eventId") UUID eventId, @Param("queueSeq") long queueSeq);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE waitlist_entries
			SET    status = 'PROMOTED', promoted_at = :now, reservation_id = :reservationId
			WHERE  id = :entryId
			  AND  status = 'WAITING'
			""")
	int markPromoted(@Param("entryId") UUID entryId, @Param("reservationId") UUID reservationId,
			@Param("now") Instant now);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE waitlist_entries
			SET    status = 'LEFT', left_at = :now
			WHERE  event_id = :eventId
			  AND  user_id = :userId
			  AND  status = 'WAITING'
			""")
	int leaveIfWaiting(@Param("eventId") UUID eventId, @Param("userId") UUID userId, @Param("now") Instant now);

}
