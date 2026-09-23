package com.crowdpass.notification;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Runs the consumer's long-poll loop on a dedicated thread while the application is running. */
@Component
@ConditionalOnProperty(name = "crowdpass.messaging.consumer.enabled", havingValue = "true")
class NotificationConsumerRunner implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(NotificationConsumerRunner.class);
	private static final Duration ERROR_BACKOFF = Duration.ofSeconds(5);

	private final NotificationConsumer consumer;
	private volatile boolean running;
	private Thread thread;

	NotificationConsumerRunner(NotificationConsumer consumer) {
		this.consumer = consumer;
	}

	@Override
	public void start() {
		running = true;
		thread = Thread.ofPlatform().name("notification-consumer").daemon().start(this::loop);
	}

	@Override
	public void stop() {
		running = false;
		if (thread != null) {
			thread.interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void loop() {
		while (running) {
			try {
				consumer.pollOnce();
			}
			catch (RuntimeException ex) {
				if (!running) {
					return;
				}
				log.warn("Notification polling failed; retrying in {} (error={})", ERROR_BACKOFF,
						ex.getClass().getSimpleName());
				try {
					Thread.sleep(ERROR_BACKOFF);
				}
				catch (InterruptedException interrupted) {
					return;
				}
			}
		}
	}

}
