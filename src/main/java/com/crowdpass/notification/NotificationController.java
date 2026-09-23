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

import com.crowdpass.common.PageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Every operation is scoped to the authenticated user. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	private final NotificationService notificationService;

	public NotificationController(NotificationService notificationService) {
		this.notificationService = notificationService;
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
