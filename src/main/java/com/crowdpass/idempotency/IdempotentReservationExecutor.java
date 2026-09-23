package com.crowdpass.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.crowdpass.ratelimit.RateLimitPolicy;
import com.crowdpass.ratelimit.RateLimiter;
import com.crowdpass.reservation.ReservationResponse;
import com.crowdpass.reservation.ReservationService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit commit boundary for a keyed reservation: claim, rate limit, seat mutation, and response
 * snapshot either all commit or all roll back.
 */
@Service
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotentReservationExecutor {

	private static final Logger log = LoggerFactory.getLogger(IdempotentReservationExecutor.class);
	private static final int CREATED = 201;

	private final IdempotencyRecordStore store;
	private final ReservationService reservationService;
	private final RateLimiter rateLimiter;
	private final JsonMapper jsonMapper;
	private final Clock clock;
	private final Duration retention;
	private final TransactionTemplate transactions;
	private final Counter executions;
	private final Counter replays;
	private final Counter conflicts;
	private final Counter rollbacks;

	public IdempotentReservationExecutor(IdempotencyRecordStore store, ReservationService reservationService,
			RateLimiter rateLimiter, JsonMapper jsonMapper, Clock clock, PlatformTransactionManager transactionManager,
			IdempotencyProperties properties, MeterRegistry meterRegistry) {
		this.store = store;
		this.reservationService = reservationService;
		this.rateLimiter = rateLimiter;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
		this.retention = requirePositive(properties.retention(), "crowdpass.idempotency.retention");
		this.transactions = new TransactionTemplate(transactionManager);
		this.executions = counter(meterRegistry, "execution");
		this.replays = counter(meterRegistry, "replay");
		this.conflicts = counter(meterRegistry, "key_conflict");
		this.rollbacks = counter(meterRegistry, "rollback");
	}

	public IdempotentResponse reserve(UUID eventId, UUID userId, byte[] keyHash, byte[] fingerprint) {
		AtomicBoolean owner = new AtomicBoolean();
		try {
			IdempotentResponse result = transactions.execute(status -> execute(eventId, userId, keyHash, fingerprint, owner));
			if (result == null) {
				throw new IllegalStateException("Idempotent reservation transaction returned no result");
			}
			if (result.replay()) {
				replays.increment();
			}
			else {
				executions.increment();
			}
			return result;
		}
		catch (IdempotencyKeyReusedException ex) {
			conflicts.increment();
			throw ex;
		}
		catch (RuntimeException ex) {
			if (owner.get()) {
				rollbacks.increment();
			}
			throw ex;
		}
	}

	private IdempotentResponse execute(UUID eventId, UUID userId, byte[] keyHash, byte[] fingerprint,
			AtomicBoolean owner) {
		Instant createdAt = clock.instant();
		var claim = store.tryClaim(userId, keyHash, fingerprint, createdAt);
		if (claim.isEmpty()) {
			IdempotencyRecordStore.StoredRecord existing = store.find(userId, keyHash)
					.orElseThrow(() -> new IllegalStateException("Idempotency conflict row disappeared"));
			if (!java.security.MessageDigest.isEqual(existing.fingerprint(), fingerprint)) {
				throw new IdempotencyKeyReusedException();
			}
			if (!"COMPLETED".equals(existing.state()) || existing.status() == null || existing.body() == null
					|| existing.location() == null) {
				log.error("Committed idempotency record is not completed");
				throw new IllegalStateException("Committed idempotency record is not completed");
			}
			return new IdempotentResponse(existing.status(), existing.body(), existing.location(), true);
		}

		owner.set(true);
		rateLimiter.checkUser(RateLimitPolicy.SEAT_MUTATION, userId);
		ReservationResponse reservation = reservationService.reserve(eventId, userId);
		JsonNode body = jsonMapper.readTree(jsonMapper.writeValueAsString(reservation));
		String location = "/api/reservations/" + reservation.id();
		Instant completedAt = clock.instant();
		store.complete(claim.get(), CREATED, body, location, completedAt, completedAt.plus(retention));
		return new IdempotentResponse(CREATED, body, location, false);
	}

	private static Counter counter(MeterRegistry registry, String outcome) {
		return registry.counter("crowdpass.idempotency.requests", "outcome", outcome);
	}

	private static Duration requirePositive(Duration value, String property) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException(property + " must be positive");
		}
		return value;
	}

}
