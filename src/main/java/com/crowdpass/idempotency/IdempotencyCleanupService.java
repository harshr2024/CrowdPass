package com.crowdpass.idempotency;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotencyCleanupService {

	private final IdempotencyRecordStore store;
	private final Clock clock;
	private final int batchSize;
	private final TransactionTemplate transactions;

	public IdempotencyCleanupService(IdempotencyRecordStore store, Clock clock, IdempotencyProperties properties,
			PlatformTransactionManager transactionManager) {
		this.store = store;
		this.clock = clock;
		this.batchSize = properties.cleanup() == null ? 0 : properties.cleanup().batchSize();
		if (batchSize < 1) {
			throw new IllegalStateException("crowdpass.idempotency.cleanup.batch-size must be at least 1");
		}
		Duration fixedDelay = properties.cleanup().fixedDelay();
		if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
			throw new IllegalStateException("crowdpass.idempotency.cleanup.fixed-delay must be positive");
		}
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** Deletes at most one configured batch. */
	public int cleanupOnce() {
		Integer deleted = transactions.execute(status -> store.deleteExpiredCompleted(clock.instant(), batchSize));
		return deleted == null ? 0 : deleted;
	}

}
