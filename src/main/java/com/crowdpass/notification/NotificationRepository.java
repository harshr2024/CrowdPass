package com.crowdpass.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationRepository extends JpaRepository<Notification, UUID> {

	/**
	 * Inserts the notification unless one already exists for {@code sourceEventId}. Returns 1 if
	 * created, 0 for a duplicate delivery. Any other constraint violation still fails.
	 */
	@Modifying
	@Query(nativeQuery = true, value = """
			INSERT INTO notifications (id, source_event_id, user_id, type, event_id, reservation_id,
			                           occurred_at, created_at)
			VALUES (:id, :sourceEventId, :userId, :type, :eventId, :reservationId, :occurredAt, :createdAt)
			ON CONFLICT (source_event_id) DO NOTHING
			""")
	int insertIfAbsent(@Param("id") UUID id, @Param("sourceEventId") UUID sourceEventId, @Param("userId") UUID userId,
			@Param("type") String type, @Param("eventId") UUID eventId, @Param("reservationId") UUID reservationId,
			@Param("occurredAt") Instant occurredAt, @Param("createdAt") Instant createdAt);

	@Query(value = """
			select new com.crowdpass.notification.NotificationResponse(
			    n.id, n.type, n.eventId, e.name, n.reservationId, n.occurredAt, n.readAt)
			from Notification n join Event e on e.id = n.eventId
			where n.userId = :userId
			order by n.occurredAt desc, n.id desc
			""",
			countQuery = "select count(n) from Notification n where n.userId = :userId")
	Page<NotificationResponse> findPageForUser(@Param("userId") UUID userId, Pageable pageable);

	@Query("""
			select new com.crowdpass.notification.NotificationResponse(
			    n.id, n.type, n.eventId, e.name, n.reservationId, n.occurredAt, n.readAt)
			from Notification n join Event e on e.id = n.eventId
			where n.id = :id and n.userId = :userId
			""")
	Optional<NotificationResponse> findForUser(@Param("id") UUID id, @Param("userId") UUID userId);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE notifications SET read_at = :now
			WHERE  id = :id AND user_id = :userId AND read_at IS NULL
			""")
	int markRead(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

}
