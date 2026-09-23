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

import com.crowdpass.ratelimit.RateLimitPolicy;
import com.crowdpass.ratelimit.RateLimiter;

/** Entries are addressed by the caller's identity, so every operation is owner-scoped. */
@RestController
@RequestMapping("/api/events/{eventId}/waitlist")
public class WaitlistController {

	private final WaitlistService waitlistService;
	private final RateLimiter rateLimiter;

	public WaitlistController(WaitlistService waitlistService, RateLimiter rateLimiter) {
		this.waitlistService = waitlistService;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping
	public ResponseEntity<WaitlistEntryResponse> join(@PathVariable("eventId") UUID eventId,
			@AuthenticationPrincipal Jwt jwt) {
		UUID userId = userId(jwt);
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, userId);
		WaitlistEntryResponse entry = waitlistService.join(eventId, userId);
		return ResponseEntity.created(URI.create("/api/events/" + eventId + "/waitlist/me")).body(entry);
	}

	@GetMapping("/me")
	public WaitlistEntryResponse getMyEntry(@PathVariable("eventId") UUID eventId, @AuthenticationPrincipal Jwt jwt) {
		return waitlistService.getMyEntry(eventId, userId(jwt));
	}

	@PostMapping("/me/leave")
	public WaitlistEntryResponse leave(@PathVariable("eventId") UUID eventId, @AuthenticationPrincipal Jwt jwt) {
		UUID userId = userId(jwt);
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, userId);
		return waitlistService.leave(eventId, userId);
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
