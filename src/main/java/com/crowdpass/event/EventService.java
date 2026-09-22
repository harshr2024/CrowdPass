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
	static final Sort START_TIME_ORDER = Sort.by(Sort.Order.asc("startsAt"), Sort.Order.asc("id"));

	private final EventRepository eventRepository;
	private final Clock clock;

	public EventService(EventRepository eventRepository, Clock clock) {
		this.eventRepository = eventRepository;
		this.clock = clock;
	}

	/** Published events that have not yet ended. */
	@Transactional(readOnly = true)
	public PageResponse<EventResponse> listPublishedEvents(int page, int size) {
		PageRequest pageRequest = PageRequest.of(page, size, START_TIME_ORDER);
		return PageResponse.from(eventRepository
				.findByStatusAndEndsAtAfter(EventStatus.PUBLISHED, clock.instant(), pageRequest)
				.map(EventResponse::from));
	}

	@Transactional(readOnly = true)
	public EventResponse getPublishedEvent(UUID id) {
		return eventRepository.findByIdAndStatus(id, EventStatus.PUBLISHED)
				.map(EventResponse::from)
				.orElseThrow(EventNotFoundException::new);
	}

	/** Events owned by {@code organizerId} in any status. Ownership is part of the query itself. */
	@Transactional(readOnly = true)
	public PageResponse<OrganizerEventResponse> listOrganizerEvents(UUID organizerId, int page, int size) {
		PageRequest pageRequest = PageRequest.of(page, size, START_TIME_ORDER);
		return PageResponse.from(eventRepository.findByOrganizerId(organizerId, pageRequest)
				.map(OrganizerEventResponse::from));
	}

}
