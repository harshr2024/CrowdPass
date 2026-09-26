package com.crowdpass.reservation;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crowdpass.idempotency.IdempotencyKeys;
import com.crowdpass.idempotency.IdempotentReservationExecutor;
import com.crowdpass.idempotency.IdempotentResponse;
import com.crowdpass.idempotency.ReservationRequestFingerprint;
import com.crowdpass.ratelimit.RateLimitPolicy;
import com.crowdpass.ratelimit.RateLimiter;
import com.crowdpass.common.PageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
public class ReservationController {

	private final ReservationService reservationService;
	private final RateLimiter rateLimiter;
	private final IdempotencyKeys idempotencyKeys;
	private final ReservationRequestFingerprint fingerprint;
	private final IdempotentReservationExecutor idempotentExecutor;

	public ReservationController(ReservationService reservationService, RateLimiter rateLimiter,
			IdempotencyKeys idempotencyKeys, ReservationRequestFingerprint fingerprint,
			IdempotentReservationExecutor idempotentExecutor) {
		this.reservationService = reservationService;
		this.rateLimiter = rateLimiter;
		this.idempotencyKeys = idempotencyKeys;
		this.fingerprint = fingerprint;
		this.idempotentExecutor = idempotentExecutor;
	}

	@PostMapping("/api/events/{eventId}/reservations")
	public ResponseEntity<?> reserve(@PathVariable("eventId") UUID eventId, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) List<String> idempotencyKeyValues) {
		UUID userId = userId(jwt);
		var keyHash = idempotencyKeys.validateAndHash(idempotencyKeyValues);
		if (keyHash.isPresent()) {
			IdempotentResponse response = idempotentExecutor.reserve(eventId, userId, keyHash.get(),
					fingerprint.create(eventId));
			return ResponseEntity.status(response.status())
					.location(URI.create(response.location()))
					.body(response.body());
		}
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, userId);
		ReservationResponse reservation = reservationService.reserve(eventId, userId);
		return ResponseEntity.created(URI.create("/api/reservations/" + reservation.id())).body(reservation);
	}

	@GetMapping("/api/reservations/{reservationId}")
	public ReservationResponse getReservation(@PathVariable("reservationId") UUID reservationId,
			@AuthenticationPrincipal Jwt jwt) {
		return reservationService.getReservation(reservationId, userId(jwt));
	}

	@GetMapping("/api/events/{eventId}/reservation/me")
	public ReservationResponse getActiveReservation(@PathVariable("eventId") UUID eventId,
			@AuthenticationPrincipal Jwt jwt) {
		return reservationService.getActiveReservation(eventId, userId(jwt));
	}

	@GetMapping("/api/reservations")
	public PageResponse<ReservationResponse> listReservations(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "" + PageResponse.DEFAULT_SIZE) @Min(1)
			@Max(PageResponse.MAX_SIZE) int size) {
		return reservationService.listReservations(userId(jwt), page, size);
	}

	@PostMapping("/api/reservations/{reservationId}/cancel")
	public ReservationResponse cancel(@PathVariable("reservationId") UUID reservationId,
			@AuthenticationPrincipal Jwt jwt) {
		UUID userId = userId(jwt);
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, userId);
		return reservationService.cancel(reservationId, userId);
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
