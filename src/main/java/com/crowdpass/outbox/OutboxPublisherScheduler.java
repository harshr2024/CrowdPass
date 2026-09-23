package com.crowdpass.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Runs the publisher on a fixed delay and refreshes the pending gauges. A permanently stuck event is
 * visible as a growing {@code crowdpass.outbox.oldest_pending_age_seconds}; there is no dead state.
 */
@Component
@ConditionalOnProperty(name = "crowdpass.messaging.publisher.enabled", havingValue = "true")
class OutboxPublisherScheduler {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

	private final OutboxPublisher publisher;
	private final OutboxEventRepository repository;
	private final Clock clock;
	private final AtomicLong pending = new AtomicLong();
	private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

	OutboxPublisherScheduler(OutboxPublisher publisher, OutboxEventRepository repository, Clock clock,
			MeterRegistry meterRegistry) {
		this.publisher = publisher;
		this.repository = repository;
		this.clock = clock;
		Gauge.builder("crowdpass.outbox.pending", pending, AtomicLong::get).register(meterRegistry);
		Gauge.builder("crowdpass.outbox.oldest_pending_age_seconds", oldestPendingAgeSeconds, AtomicLong::get)
				.register(meterRegistry);
		refreshGauges();
	}

	@Scheduled(fixedDelayString = "${crowdpass.messaging.publisher.fixed-delay:PT1S}")
	void publish() {
		try {
			publisher.publishAllDue();
		}
		catch (RuntimeException ex) {
			log.warn("Outbox publishing run failed (error={})", ex.getClass().getSimpleName());
		}
		finally {
			refreshGauges();
		}
	}

	@Scheduled(fixedDelayString = "${crowdpass.messaging.publisher.metrics-refresh:PT30S}")
	void refreshGauges() {
		pending.set(repository.countPending());
		Instant oldest = repository.oldestPendingOccurredAt();
		oldestPendingAgeSeconds.set(oldest == null ? 0 : Duration.between(oldest, clock.instant()).toSeconds());
	}

}
