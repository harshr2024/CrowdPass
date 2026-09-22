package com.crowdpass.event;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/events")
public class EventController {

	static final int MAX_PAGE_SIZE = 100;

	private final EventService eventService;

	public EventController(EventService eventService) {
		this.eventService = eventService;
	}

	@GetMapping
	public EventPageResponse listEvents(
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		return eventService.listPublishedEvents(page, size);
	}

	@GetMapping("/{id}")
	public EventResponse getEvent(@PathVariable("id") UUID id) {
		return eventService.getPublishedEvent(id);
	}

}
