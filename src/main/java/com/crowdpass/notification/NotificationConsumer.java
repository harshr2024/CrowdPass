package com.crowdpass.notification;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.crowdpass.config.MessagingProperties;
import com.crowdpass.config.NotificationQueue;
import com.crowdpass.outbox.MessageEnvelope;
import com.crowdpass.outbox.MessageEnvelope.InvalidEnvelopeException;
import com.crowdpass.reservation.WaitlistPromotedEvent;

import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes notification events from standard SQS (at-least-once, unordered).
 *
 * <p>Per message: parse with a tolerant reader, validate type/version/fields, insert the
 * notification with ON CONFLICT (source_event_id) DO NOTHING, commit, and only then delete the
 * message. A failure at any step leaves the message in the queue: it becomes visible again after
 * the visibility timeout and, after the queue's maxReceiveCount, moves to the dead-letter queue.
 * Nothing is discarded silently.
 *
 * <p>The 30s queue visibility timeout assumes processing one received batch stays well below it.
 * If processing ever approaches it, reduce the batch size or extend visibility; the timeout does
 * not by itself prevent duplicates.
 */
@Component
public class NotificationConsumer {

	private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

	/** Separate from the HTTP mapper, which rejects unknown fields; the tree reader ignores them. */
	private static final JsonMapper MESSAGE_READER = JsonMapper.builder().build();

	private final SqsClient sqsClient;
	private final NotificationQueue queue;
	private final NotificationService notificationService;
	private final MeterRegistry meterRegistry;
	private final MessagingProperties.Consumer settings;

	NotificationConsumer(SqsClient sqsClient, NotificationQueue queue, NotificationService notificationService,
			MeterRegistry meterRegistry, MessagingProperties properties) {
		this.sqsClient = sqsClient;
		this.queue = queue;
		this.notificationService = notificationService;
		this.meterRegistry = meterRegistry;
		this.settings = properties.consumer();
	}

	/** Receives one batch (long polling) and processes it. Returns the number of messages received. */
	public int pollOnce() {
		int waitSeconds = (int) settings.waitTime().toSeconds();
		List<Message> messages = sqsClient.receiveMessage(request -> request
				.queueUrl(queue.url())
				.maxNumberOfMessages(settings.maxMessages())
				.waitTimeSeconds(waitSeconds)
				.overrideConfiguration(config -> config
						.apiCallTimeout(Duration.ofSeconds(waitSeconds + 10L))
						.apiCallAttemptTimeout(Duration.ofSeconds(waitSeconds + 5L))))
				.messages();
		for (Message message : messages) {
			process(message);
		}
		return messages.size();
	}

	private void process(Message message) {
		MessageEnvelope envelope;
		try {
			envelope = MessageEnvelope.parse(message.body(), MESSAGE_READER);
		}
		catch (InvalidEnvelopeException ex) {
			fail(message, null, "malformed");
			return;
		}
		try {
			boolean created = handle(envelope);
			meterRegistry.counter("crowdpass.notifications.consumed", "outcome", created ? "created" : "duplicate",
					"reason", "none").increment();
			if (!created) {
				log.debug("Duplicate delivery of event {}", envelope.id());
			}
		}
		catch (InvalidEnvelopeException ex) {
			fail(message, envelope.id(), "malformed");
			return;
		}
		catch (UnsupportedMessageException ex) {
			fail(message, envelope.id(), ex.reason);
			return;
		}
		catch (DataAccessException ex) {
			fail(message, envelope.id(), "database");
			return;
		}
		catch (RuntimeException ex) {
			fail(message, envelope.id(), "unexpected");
			return;
		}
		acknowledge(message);
	}

	/** Commits the notification (or finds it already recorded) before returning. */
	private boolean handle(MessageEnvelope envelope) {
		if (!WaitlistPromotedEvent.TYPE.equals(envelope.type())) {
			throw new UnsupportedMessageException("unsupported_type");
		}
		if (envelope.version() != WaitlistPromotedEvent.VERSION) {
			throw new UnsupportedMessageException("unsupported_version");
		}
		WaitlistPromotedEvent event = new WaitlistPromotedEvent(
				MessageEnvelope.uuid(envelope.data(), "userId"),
				MessageEnvelope.uuid(envelope.data(), "eventId"),
				MessageEnvelope.uuid(envelope.data(), "reservationId"),
				MessageEnvelope.uuid(envelope.data(), "waitlistEntryId"));
		return notificationService.recordWaitlistPromoted(envelope.id(), envelope.occurredAt(), event);
	}

	/** After commit. If deletion fails the message is redelivered and absorbed as a duplicate. */
	private void acknowledge(Message message) {
		try {
			sqsClient.deleteMessage(request -> request.queueUrl(queue.url()).receiptHandle(message.receiptHandle()));
		}
		catch (SdkException ex) {
			meterRegistry.counter("crowdpass.notifications.ack_failures").increment();
			log.warn("Could not delete processed message {}; it will be redelivered (error={})", message.messageId(),
					ex.getClass().getSimpleName());
		}
	}

	/**
	 * Leaves the message in the queue for redelivery and, eventually, the dead-letter queue.
	 * {@code reason} is a fixed category. The body is never logged.
	 */
	private void fail(Message message, UUID envelopeId, String reason) {
		meterRegistry.counter("crowdpass.notifications.consumed", "outcome", "failed", "reason", reason).increment();
		log.warn("Could not process message {} envelope {} (reason={}); leaving it for redelivery", message.messageId(),
				envelopeId == null ? "unparsed" : envelopeId, reason);
	}

	static final class UnsupportedMessageException extends RuntimeException {

		private final String reason;

		UnsupportedMessageException(String reason) {
			super(reason);
			this.reason = reason;
		}

	}

}
