package com.crowdpass.event;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Requires ORGANIZER capability (SecurityConfiguration); results are scoped to the caller. */
@RestController
@RequestMapping("/api/organizer/events")
public class OrganizerEventController {

	private final EventService eventService;

	public OrganizerEventController(EventService eventService) {
		this.eventService = eventService;
	}

	@GetMapping
	public PageResponse<OrganizerEventResponse> listMyEvents(
			@AuthenticationPrincipal Jwt jwt,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "" + EventController.DEFAULT_PAGE_SIZE) @Min(1)
			@Max(EventController.MAX_PAGE_SIZE) int size) {
		return eventService.listOrganizerEvents(UUID.fromString(jwt.getSubject()), page, size);
	}

}
