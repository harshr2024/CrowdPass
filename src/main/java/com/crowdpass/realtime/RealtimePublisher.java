package com.crowdpass.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** Best-effort ephemeral publication. Durable notification commits never depend on Redis. */
@Component
class RealtimePublisher {

	private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

	private final StringRedisTemplate redis;
	private final JsonMapper json;
	private final MeterRegistry meters;

	RealtimePublisher(StringRedisTemplate redis, JsonMapper json, MeterRegistry meters) {
		this.redis = redis;
		this.json = json;
		this.meters = meters;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publish(NotificationCreated event) {
		try {
			RealtimeSignal signal = RealtimeSignal.notificationCreated(event.userId(), event.notificationId());
			redis.convertAndSend(RealtimeConfiguration.CHANNEL, json.writeValueAsString(signal));
			meters.counter("crowdpass.realtime.signals.published", "outcome", "success").increment();
		}
		catch (RuntimeException ex) {
			meters.counter("crowdpass.realtime.signals.published", "outcome", "failed").increment();
			log.warn("Could not publish realtime notification signal (error={})", ex.getClass().getSimpleName());
		}
	}
}
