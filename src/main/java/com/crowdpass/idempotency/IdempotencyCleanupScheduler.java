package com.crowdpass.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class IdempotencyCleanupScheduler {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

	private final IdempotencyCleanupService cleanupService;

	IdempotencyCleanupScheduler(IdempotencyCleanupService cleanupService) {
		this.cleanupService = cleanupService;
	}

	@Scheduled(fixedDelayString = "${crowdpass.idempotency.cleanup.fixed-delay:PT1H}")
	void cleanup() {
		try {
			cleanupService.cleanupOnce();
		}
		catch (RuntimeException ex) {
			log.warn("Idempotency cleanup failed (error={})", ex.getClass().getSimpleName());
		}
	}

}
