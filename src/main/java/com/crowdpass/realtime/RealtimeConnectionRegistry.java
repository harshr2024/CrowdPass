package com.crowdpass.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.core.instrument.MeterRegistry;

/** Instance-local ownership and lifecycle for authenticated SSE connections. */
@Component
public class RealtimeConnectionRegistry implements DisposableBean {

	private final Object monitor = new Object();
	private final Map<UUID, Map<UUID, Connection>> byUser = new HashMap<>();
	private final AtomicInteger active = new AtomicInteger();
	private final Executor deliveryExecutor;
	private final ScheduledExecutorService scheduler;
	private final RealtimeProperties properties;
	private final Clock clock;
	private final MeterRegistry meters;
	private final LongFunction<SseEmitter> emitterFactory;

	@Autowired
	RealtimeConnectionRegistry(@Qualifier("realtimeDeliveryExecutor") Executor deliveryExecutor,
			@Qualifier("realtimeScheduler") ScheduledExecutorService scheduler, RealtimeProperties properties,
			Clock clock, MeterRegistry meters) {
		this(deliveryExecutor, scheduler, properties, clock, meters, SseEmitter::new);
	}

	RealtimeConnectionRegistry(Executor deliveryExecutor, ScheduledExecutorService scheduler,
			RealtimeProperties properties, Clock clock, MeterRegistry meters, LongFunction<SseEmitter> emitterFactory) {
		this.deliveryExecutor = deliveryExecutor;
		this.scheduler = scheduler;
		this.properties = properties;
		this.clock = clock;
		this.meters = meters;
		this.emitterFactory = emitterFactory;
		meters.gauge("crowdpass.realtime.connections", active);
		scheduler.scheduleAtFixedRate(this::heartbeat, properties.heartbeat().toMillis(),
				properties.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
	}

	public SseEmitter open(UUID userId, Instant tokenExpiresAt, String lastEventId) {
		Instant now = clock.instant();
		if (tokenExpiresAt == null || !tokenExpiresAt.isAfter(now)) {
			throw new RealtimeAuthenticationExpired();
		}
		Duration lifetime = Duration.between(now, tokenExpiresAt);
		if (lifetime.compareTo(properties.maxStreamLifetime()) > 0) {
			lifetime = properties.maxStreamLifetime();
		}
		Connection connection = new Connection(UUID.randomUUID(), userId, emitterFactory.apply(lifetime.toMillis()));
		register(connection);
		connection.emitter.onCompletion(() -> remove(connection, "completed"));
		connection.emitter.onTimeout(() -> close(connection, "timeout"));
		connection.emitter.onError(error -> remove(connection, "error"));
		try {
			ScheduledFuture<?> expiration = scheduler.schedule(() -> close(connection, "lifetime"),
					lifetime.toMillis(), TimeUnit.MILLISECONDS);
			connection.expiration = expiration;
			if (connection.closed.get()) {
				expiration.cancel(false);
			}
			submit(connection, () -> connection.send(SseEmitter.event()
					.name("notifications.sync")
					.data("{\"version\":1,\"resource\":\"notifications\"}")));
		}
		catch (RejectedExecutionException ex) {
			close(connection, "rejected");
			meters.counter("crowdpass.realtime.connections.rejected", "reason", "executor_saturated").increment();
			throw new RealtimeUnavailable();
		}
		meters.counter("crowdpass.realtime.connections.opened").increment();
		if (lastEventId != null && !lastEventId.isBlank()) {
			// This is observability only. Durable recovery always uses GET /api/notifications.
			meters.counter("crowdpass.realtime.reconnects", "last_event_id", "present").increment();
		}
		return connection.emitter;
	}

	/** Called by the Redis listener; it only submits bounded work and never performs socket I/O. */
	void dispatch(UUID userId, UUID notificationId) {
		for (Connection connection : snapshot(userId)) {
			try {
				submit(connection, () -> {
					connection.send(SseEmitter.event()
							.id(notificationId.toString())
							.name("notifications.changed")
							.data("{\"notificationId\":\"" + notificationId + "\"}"));
					meters.counter("crowdpass.realtime.signals.delivered").increment();
				});
			}
			catch (RejectedExecutionException ex) {
				meters.counter("crowdpass.realtime.signals.dropped", "reason", "executor_saturated").increment();
			}
		}
	}

	public int activeConnectionCount() {
		return active.get();
	}

	private void register(Connection connection) {
		synchronized (monitor) {
			Map<UUID, Connection> owned = byUser.computeIfAbsent(connection.userId, ignored -> new HashMap<>());
			if (owned.size() >= properties.maxConnectionsPerUser()
					|| active.get() >= properties.maxConnectionsPerInstance()) {
				if (owned.isEmpty()) {
					byUser.remove(connection.userId);
				}
				meters.counter("crowdpass.realtime.connections.rejected", "reason", "connection_limit").increment();
				throw new RealtimeConnectionLimitExceeded();
			}
			owned.put(connection.id, connection);
			active.incrementAndGet();
		}
	}

	private Connection[] snapshot(UUID userId) {
		synchronized (monitor) {
			Map<UUID, Connection> owned = byUser.get(userId);
			return owned == null ? new Connection[0] : owned.values().toArray(Connection[]::new);
		}
	}

	private Connection[] snapshotAll() {
		synchronized (monitor) {
			return byUser.values().stream().flatMap(connections -> connections.values().stream())
					.toArray(Connection[]::new);
		}
	}

	private void heartbeat() {
		for (Connection connection : snapshotAll()) {
			try {
				submit(connection, () -> connection.send(SseEmitter.event().comment("keepalive")));
			}
			catch (RejectedExecutionException ex) {
				meters.counter("crowdpass.realtime.signals.dropped", "reason", "executor_saturated").increment();
			}
		}
	}

	private void submit(Connection connection, IoAction action) {
		deliveryExecutor.execute(() -> {
			if (connection.closed.get()) {
				return;
			}
			try {
				action.run();
			}
			catch (IOException | RuntimeException ex) {
				meters.counter("crowdpass.realtime.delivery.failures").increment();
				close(connection, "send_failure");
			}
		});
	}

	private void close(Connection connection, String reason) {
		if (remove(connection, reason)) {
			try {
				connection.emitter.complete();
			}
			catch (RuntimeException ignored) {
				// The client or servlet container may already have closed it.
			}
		}
	}

	private boolean remove(Connection connection, String reason) {
		if (!connection.closed.compareAndSet(false, true)) {
			return false;
		}
		synchronized (monitor) {
			Map<UUID, Connection> owned = byUser.get(connection.userId);
			if (owned != null) {
				owned.remove(connection.id);
				if (owned.isEmpty()) {
					byUser.remove(connection.userId);
				}
			}
			active.decrementAndGet();
		}
		meters.counter("crowdpass.realtime.connections.closed", "reason", reason).increment();
		ScheduledFuture<?> expiration = connection.expiration;
		if (expiration != null) {
			expiration.cancel(false);
		}
		return true;
	}

	@Override
	public void destroy() {
		closeAllForShutdown();
	}

	/** Close streams before the web server waits for in-flight requests during graceful shutdown. */
	@EventListener(ContextClosedEvent.class)
	void onContextClosed() {
		closeAllForShutdown();
	}

	private void closeAllForShutdown() {
		for (Connection connection : snapshotAll()) {
			close(connection, "shutdown");
		}
	}

	private static final class Connection {
		private final UUID id;
		private final UUID userId;
		private final SseEmitter emitter;
		private final AtomicBoolean closed = new AtomicBoolean();
		private volatile ScheduledFuture<?> expiration;

		private Connection(UUID id, UUID userId, SseEmitter emitter) {
			this.id = id;
			this.userId = userId;
			this.emitter = emitter;
		}

		private synchronized void send(SseEmitter.SseEventBuilder event) throws IOException {
			emitter.send(event);
		}
	}

	@FunctionalInterface
	private interface IoAction {
		void run() throws IOException;
	}
}
