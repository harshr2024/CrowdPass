package com.crowdpass.notification;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.crowdpass.common.PageResponse;
import com.crowdpass.realtime.RealtimeConnectionRegistry;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Every operation is scoped to the authenticated user. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	private final NotificationService notificationService;
	private final RealtimeConnectionRegistry realtimeConnections;

	public NotificationController(NotificationService notificationService,
			RealtimeConnectionRegistry realtimeConnections) {
		this.notificationService = notificationService;
		this.realtimeConnections = realtimeConnections;
	}

	@GetMapping(path = "/stream")
	public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
		SseEmitter emitter = realtimeConnections.open(userId(jwt), jwt.getExpiresAt(), lastEventId);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.contentType(MediaType.TEXT_EVENT_STREAM)
				.body(emitter);
	}

	@GetMapping
	public PageResponse<NotificationResponse> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "" + PageResponse.DEFAULT_SIZE) @Min(1)
			@Max(PageResponse.MAX_SIZE) int size) {
		return notificationService.list(userId(jwt), page, size);
	}

	@PostMapping("/{notificationId}/read")
	public NotificationResponse markRead(@PathVariable("notificationId") UUID notificationId,
			@AuthenticationPrincipal Jwt jwt) {
		return notificationService.markRead(notificationId, userId(jwt));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
