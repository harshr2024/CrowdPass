package com.crowdpass.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	/**
	 * Locks up to {@code limit} due, unpublished events. SKIP LOCKED lets concurrent publishers
	 * take disjoint rows instead of waiting for each other.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	@Query(nativeQuery = true, value = """
			SELECT * FROM outbox_events
			WHERE  published_at IS NULL
			  AND  next_attempt_at <= :now
			ORDER  BY next_attempt_at, id
			LIMIT  :limit
			FOR UPDATE SKIP LOCKED
			""")
	List<OutboxEvent> lockDue(@Param("now") Instant now, @Param("limit") int limit);

	@Query(nativeQuery = true, value = "SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
	long countPending();

	@Query(nativeQuery = true, value = "SELECT min(occurred_at) FROM outbox_events WHERE published_at IS NULL")
	Instant oldestPendingOccurredAt();

}
