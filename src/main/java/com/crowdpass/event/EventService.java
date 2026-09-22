package com.crowdpass.event;

import java.time.Clock;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

	/** Soonest first; id breaks ties so pages are stable when start times are equal. */
	static final Sort PUBLIC_LISTING_ORDER = Sort.by(Sort.Order.asc("startsAt"), Sort.Order.asc("id"));

	private final EventRepository eventRepository;
	private final Clock clock;

	public EventService(EventRepository eventRepository, Clock clock) {
		this.eventRepository = eventRepository;
		this.clock = clock;
	}

	/** Published events that have not yet ended. */
	@Transactional(readOnly = true)
	public EventPageResponse listPublishedEvents(int page, int size) {
		PageRequest pageRequest = PageRequest.of(page, size, PUBLIC_LISTING_ORDER);
		return EventPageResponse.from(eventRepository
				.findByStatusAndEndsAtAfter(EventStatus.PUBLISHED, clock.instant(), pageRequest)
				.map(EventResponse::from));
	}

	@Transactional(readOnly = true)
	public EventResponse getPublishedEvent(UUID id) {
		return eventRepository.findByIdAndStatus(id, EventStatus.PUBLISHED)
				.map(EventResponse::from)
				.orElseThrow(EventNotFoundException::new);
	}

}
