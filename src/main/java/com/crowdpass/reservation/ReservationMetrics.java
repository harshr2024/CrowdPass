package com.crowdpass.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.crowdpass.exception.ApiException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Low-cardinality, commit-aware metrics for authoritative seat and waitlist mutations. */
@Component
final class ReservationMetrics {

	private static final Logger log = LoggerFactory.getLogger(ReservationMetrics.class);

	private final MeterRegistry meters;

	ReservationMetrics(MeterRegistry meters) {
		this.meters = meters;
	}

	Timer.Sample start() {
		return Timer.start(meters);
	}

	void reservationCommitted(Timer.Sample sample, String operation, String outcome) {
		afterCommit(() -> stop(sample, "crowdpass.reservations.operations", operation, outcome));
	}

	void waitlistCommitted(Timer.Sample sample, String operation, String outcome) {
		afterCommit(() -> stop(sample, "crowdpass.waitlist.operations", operation, outcome));
	}

	void promotionCommitted() {
		afterCommit(() -> meters.counter("crowdpass.waitlist.promotions").increment());
	}

	void reservationRejected(Timer.Sample sample, String operation, ApiException failure) {
		stop(sample, "crowdpass.reservations.operations", operation, bounded(failure));
	}

	void waitlistRejected(Timer.Sample sample, String operation, ApiException failure) {
		stop(sample, "crowdpass.waitlist.operations", operation, bounded(failure));
	}

	void unexpected(Timer.Sample sample, String metric, String operation, RuntimeException failure) {
		stop(sample, metric, operation, "failed");
		log.warn("Domain mutation failed unexpectedly (operation={}, error={})", operation,
				failure.getClass().getSimpleName());
	}

	private void stop(Timer.Sample sample, String name, String operation, String outcome) {
		sample.stop(Timer.builder(name)
				.tag("operation", operation)
				.tag("outcome", outcome)
				.register(meters));
	}

	private static void afterCommit(Runnable action) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("Domain metrics require an active transaction");
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}

	/** Stable API codes are collapsed to a finite operator-facing vocabulary. */
	private static String bounded(ApiException failure) {
		return switch (failure.getCode()) {
			case "EVENT_FULL" -> "event_full";
			case "ALREADY_RESERVED" -> "already_reserved";
			case "REGISTRATION_NOT_OPEN" -> "registration_not_open";
			case "REGISTRATION_CLOSED" -> "registration_closed";
			case "EVENT_NOT_FOUND" -> "event_not_found";
			case "RESERVATION_NOT_FOUND" -> "reservation_not_found";
			case "CANCELLATION_CLOSED" -> "cancellation_closed";
			case "ALREADY_WAITLISTED" -> "already_waitlisted";
			case "SEAT_AVAILABLE" -> "seat_available";
			case "WAITLIST_ENTRY_NOT_FOUND" -> "waitlist_entry_not_found";
			case "ALREADY_PROMOTED" -> "already_promoted";
			case "UNAUTHENTICATED" -> "unauthenticated";
			default -> "other_rejected";
		};
	}
}
