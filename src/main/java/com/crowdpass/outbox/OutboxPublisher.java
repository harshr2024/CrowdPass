package com.crowdpass.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.crowdpass.config.MessagingProperties;
import com.crowdpass.config.NotificationQueue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes pending outbox events to SQS, one batch per transaction:
 * lock up to 10 due rows (FOR UPDATE SKIP LOCKED), send them in one SendMessageBatch, mark only the
 * entries SQS acknowledged, back off the rest, commit.
 *
 * <p>The transaction deliberately stays open during the bounded SQS call (explicit SDK timeouts):
 * if anything fails before commit, the rows remain pending and are sent again. That can produce
 * duplicates (for example when SQS accepted a batch but the response was lost, or the commit
 * failed), never loss. Consumers absorb duplicates through their idempotency key, the event id.
 */
@Component
public class OutboxPublisher {

	/** SQS SendMessageBatch accepts at most 10 entries. */
	static final int MAX_BATCH_SIZE = 10;

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

	private final OutboxEventRepository repository;
	private final SqsClient sqsClient;
	private final NotificationQueue queue;
	private final JsonMapper jsonMapper;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;
	private final int batchSize;
	private final Counter published;
	private final Counter failed;

	OutboxPublisher(OutboxEventRepository repository, SqsClient sqsClient, NotificationQueue queue,
			JsonMapper jsonMapper, Clock clock, PlatformTransactionManager transactionManager,
			MessagingProperties properties, MeterRegistry meterRegistry) {
		this.repository = repository;
		this.sqsClient = sqsClient;
		this.queue = queue;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setTimeout(30);
		this.batchSize = Math.min(MAX_BATCH_SIZE, Math.max(1, properties.publisher().batchSize()));
		this.published = meterRegistry.counter("crowdpass.outbox.publish", "outcome", "success");
		this.failed = meterRegistry.counter("crowdpass.outbox.publish", "outcome", "failure");
	}

	/** Publishes at most one batch. Returns the number of rows that were due (0 when idle). */
	public int publishOnce() {
		Integer due = transactionTemplate.execute(status -> publishBatch());
		return due == null ? 0 : due;
	}

	/** Publishes batches until nothing is due. */
	public void publishAllDue() {
		while (publishOnce() == batchSize) {
			// keep draining full batches
		}
	}

	private int publishBatch() {
		Instant now = clock.instant();
		List<OutboxEvent> events = repository.lockDue(now, batchSize);
		if (events.isEmpty()) {
			return 0;
		}

		List<SendMessageBatchRequestEntry> entries = new ArrayList<>(events.size());
		for (OutboxEvent event : events) {
			entries.add(SendMessageBatchRequestEntry.builder()
					.id(event.getId().toString())
					.messageBody(MessageEnvelope.toJson(event, jsonMapper))
					.build());
		}

		SendMessageBatchResponse response;
		try {
			response = sqsClient.sendMessageBatch(request -> request.queueUrl(queue.url()).entries(entries));
		}
		catch (SdkException ex) {
			// SQS may or may not have accepted the batch; keep every row pending (with backoff) so it is
			// sent again. A resend reuses the same event id.
			for (OutboxEvent event : events) {
				event.recordFailure(now, ex.getClass().getSimpleName());
			}
			failed.increment(events.size());
			log.warn("Outbox batch send failed; {} event(s) remain pending (error={})", events.size(),
					ex.getClass().getSimpleName());
			return events.size();
		}

		Map<String, OutboxEvent> byEntryId = events.stream()
				.collect(Collectors.toMap(event -> event.getId().toString(), Function.identity()));
		Set<String> handled = new HashSet<>();
		for (SendMessageBatchResultEntry ok : response.successful()) {
			OutboxEvent event = byEntryId.get(ok.id());
			if (event != null && handled.add(ok.id())) {
				event.markPublished(now);
				published.increment();
			}
		}
		for (BatchResultErrorEntry error : response.failed()) {
			OutboxEvent event = byEntryId.get(error.id());
			if (event != null && handled.add(error.id())) {
				String code = error.code() == null || error.code().isBlank() ? "SendFailed" : error.code();
				event.recordFailure(now, code + (Boolean.TRUE.equals(error.senderFault()) ? " (sender fault)" : ""));
				failed.increment();
			}
		}
		for (OutboxEvent event : events) {
			if (!handled.contains(event.getId().toString())) {
				// Neither acknowledged nor rejected: never assume success.
				event.recordFailure(now, "NoResultForEntry");
				failed.increment();
			}
		}
		if (!response.failed().isEmpty()) {
			log.warn("Outbox batch partially failed: {} of {} entries rejected", response.failed().size(), events.size());
		}
		return events.size();
	}

}
