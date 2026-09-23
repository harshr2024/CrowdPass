package com.crowdpass.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crowdpass.common.PageResponse;
import com.crowdpass.exception.ApiException;
import com.crowdpass.reservation.WaitlistPromotedEvent;

@Service
public class NotificationService {

	private final NotificationRepository repository;
	private final Clock clock;

	NotificationService(NotificationRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/** Returns true if a notification was created, false if this source event was already recorded. */
	@Transactional
	public boolean recordWaitlistPromoted(UUID sourceEventId, Instant occurredAt, WaitlistPromotedEvent event) {
		UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
		return repository.insertIfAbsent(id, sourceEventId, event.userId(), NotificationType.WAITLIST_PROMOTED.name(),
				event.eventId(), event.reservationId(), occurredAt, clock.instant()) == 1;
	}

	/** The caller's notifications, newest first. */
	@Transactional(readOnly = true)
	public PageResponse<NotificationResponse> list(UUID userId, int page, int size) {
		return PageResponse.from(repository.findPageForUser(userId, PageRequest.of(page, size)));
	}

	/** Marks the caller's notification read; repeating returns the existing state. */
	@Transactional
	public NotificationResponse markRead(UUID notificationId, UUID userId) {
		repository.markRead(notificationId, userId, clock.instant());
		return repository.findForUser(notificationId, userId).orElseThrow(NotificationNotFound::new);
	}

	static final class NotificationNotFound extends ApiException {
		NotificationNotFound() {
			super(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found.");
		}
	}

}
