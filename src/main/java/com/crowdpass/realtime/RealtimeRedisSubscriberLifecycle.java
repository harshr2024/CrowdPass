package com.crowdpass.realtime;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/** Starts and retries the optional subscriber without making application startup depend on Redis. */
@Component
class RealtimeRedisSubscriberLifecycle implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(RealtimeRedisSubscriberLifecycle.class);

	private final RedisMessageListenerContainer container;
	private final RealtimeProperties properties;
	private final MeterRegistry meters;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile Thread starter;

	RealtimeRedisSubscriberLifecycle(@Qualifier("realtimeRedisContainer") RedisMessageListenerContainer container,
			RealtimeProperties properties, MeterRegistry meters) {
		this.container = container;
		this.properties = properties;
		this.meters = meters;
	}

	@Override
	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		starter = Thread.ofPlatform().name("realtime-redis-starter").daemon().start(this::startUntilConnected);
	}

	private void startUntilConnected() {
		while (running.get() && !container.isRunning()) {
			try {
				container.start();
				meters.counter("crowdpass.realtime.redis.subscriber", "outcome", "connected").increment();
			}
			catch (RuntimeException ex) {
				meters.counter("crowdpass.realtime.redis.subscriber", "outcome", "failed").increment();
				log.warn("Realtime Redis subscriber unavailable; retrying (error={})",
						ex.getClass().getSimpleName());
				try {
					Thread.sleep(properties.redisRecoveryInterval());
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	@Override
	public void stop() {
		running.set(false);
		Thread thread = starter;
		if (thread != null) {
			thread.interrupt();
		}
		try {
			container.stop();
		}
		catch (RuntimeException ex) {
			log.debug("Realtime Redis subscriber was already unavailable during shutdown");
		}
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE - 100;
	}
}
