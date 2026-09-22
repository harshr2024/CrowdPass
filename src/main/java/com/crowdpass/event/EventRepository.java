package com.crowdpass.event;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, UUID> {

	Page<Event> findByStatusAndEndsAtAfter(EventStatus status, Instant endsAfter, Pageable pageable);

	Optional<Event> findByIdAndStatus(UUID id, EventStatus status);

	Page<Event> findByOrganizerId(UUID organizerId, Pageable pageable);

}
