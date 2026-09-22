package com.crowdpass.reservation;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

	private final ReservationService reservationService;

	public ReservationController(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	@PostMapping("/api/events/{eventId}/reservations")
	public ResponseEntity<ReservationResponse> reserve(@PathVariable("eventId") UUID eventId,
			@AuthenticationPrincipal Jwt jwt) {
		ReservationResponse reservation = reservationService.reserve(eventId, userId(jwt));
		return ResponseEntity.created(URI.create("/api/reservations/" + reservation.id())).body(reservation);
	}

	@GetMapping("/api/reservations/{reservationId}")
	public ReservationResponse getReservation(@PathVariable("reservationId") UUID reservationId,
			@AuthenticationPrincipal Jwt jwt) {
		return reservationService.getReservation(reservationId, userId(jwt));
	}

	@PostMapping("/api/reservations/{reservationId}/cancel")
	public ReservationResponse cancel(@PathVariable("reservationId") UUID reservationId,
			@AuthenticationPrincipal Jwt jwt) {
		return reservationService.cancel(reservationId, userId(jwt));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
