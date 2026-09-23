package com.crowdpass.reservation;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entries are addressed by the caller's identity, so every operation is owner-scoped. */
@RestController
@RequestMapping("/api/events/{eventId}/waitlist")
public class WaitlistController {

	private final WaitlistService waitlistService;

	public WaitlistController(WaitlistService waitlistService) {
		this.waitlistService = waitlistService;
	}

	@PostMapping
	public ResponseEntity<WaitlistEntryResponse> join(@PathVariable("eventId") UUID eventId,
			@AuthenticationPrincipal Jwt jwt) {
		WaitlistEntryResponse entry = waitlistService.join(eventId, userId(jwt));
		return ResponseEntity.created(URI.create("/api/events/" + eventId + "/waitlist/me")).body(entry);
	}

	@GetMapping("/me")
	public WaitlistEntryResponse getMyEntry(@PathVariable("eventId") UUID eventId, @AuthenticationPrincipal Jwt jwt) {
		return waitlistService.getMyEntry(eventId, userId(jwt));
	}

	@PostMapping("/me/leave")
	public WaitlistEntryResponse leave(@PathVariable("eventId") UUID eventId, @AuthenticationPrincipal Jwt jwt) {
		return waitlistService.leave(eventId, userId(jwt));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
