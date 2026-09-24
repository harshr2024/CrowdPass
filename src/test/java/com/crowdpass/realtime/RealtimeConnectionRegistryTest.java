package com.crowdpass.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class RealtimeConnectionRegistryTest {

	private static final Instant NOW = Instant.parse("2031-03-01T12:00:00Z");

	private final List<ThreadPoolExecutor> executors = new ArrayList<>();
	private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

	@AfterEach
	void shutDownExecutors() {
		executors.forEach(ThreadPoolExecutor::shutdownNow);
		schedulers.forEach(ScheduledExecutorService::shutdownNow);
	}

	@Test
	void routesOnlyToTheAuthenticatedOwnerAndDuplicateSignalsRemainHints() {
		List<FakeEmitter> emitters = new ArrayList<>();
		RealtimeConnectionRegistry registry = registry(Runnable::run, properties(3, 1000), emitters);
		UUID userA = UUID.randomUUID();
		UUID userB = UUID.randomUUID();
		registry.open(userA, NOW.plusSeconds(60), null);
		registry.open(userB, NOW.plusSeconds(60), null);
		UUID notification = UUID.randomUUID();

		registry.dispatch(userB, notification);
		registry.dispatch(userB, notification);

		assertThat(emitters.get(0).sends).isEqualTo(1); // sync only
		assertThat(emitters.get(1).sends).isEqualTo(3); // sync plus two harmless invalidations
	}

	@Test
	void everyConnectionGetsSyncAndItsTimeoutIsBoundedByJwtExpiry() {
		List<FakeEmitter> emitters = new ArrayList<>();
		RealtimeConnectionRegistry registry = registry(Runnable::run, properties(3, 1000), emitters);

		registry.open(UUID.randomUUID(), NOW.plusSeconds(7), "untrusted-last-id");

		assertThat(emitters).singleElement().satisfies(emitter -> {
			assertThat(emitter.getTimeout()).isEqualTo(7_000L);
			assertThat(emitter.sends).isEqualTo(1);
		});
	}

	@Test
	void expiredJwtIsRejectedBeforeRegistration() {
		List<FakeEmitter> emitters = new ArrayList<>();
		RealtimeConnectionRegistry registry = registry(Runnable::run, properties(3, 1000), emitters);

		assertThatThrownBy(() -> registry.open(UUID.randomUUID(), NOW, null))
				.isInstanceOf(RealtimeAuthenticationExpired.class);
		assertThat(registry.activeConnectionCount()).isZero();
	}

	@Test
	void perUserAndInstanceLimitsRejectBeforeAStreamIsReturned() {
		List<FakeEmitter> emitters = new ArrayList<>();
		RealtimeConnectionRegistry registry = registry(Runnable::run, properties(1, 2), emitters);
		UUID first = UUID.randomUUID();
		registry.open(first, NOW.plusSeconds(60), null);

		assertThatThrownBy(() -> registry.open(first, NOW.plusSeconds(60), null))
				.isInstanceOf(RealtimeConnectionLimitExceeded.class);
		registry.open(UUID.randomUUID(), NOW.plusSeconds(60), null);
		assertThatThrownBy(() -> registry.open(UUID.randomUUID(), NOW.plusSeconds(60), null))
				.isInstanceOf(RealtimeConnectionLimitExceeded.class);
		assertThat(registry.activeConnectionCount()).isEqualTo(2);
	}

	@Test
	void completionTimeoutAndSendFailureAllReleaseRegistryCounts() {
		List<FakeEmitter> emitters = new ArrayList<>();
		RealtimeConnectionRegistry registry = registry(Runnable::run, properties(3, 1000), emitters);
		UUID user = UUID.randomUUID();
		registry.open(user, NOW.plusSeconds(60), null);
		emitters.get(0).completeFromClient();
		assertThat(registry.activeConnectionCount()).isZero();

		registry.open(user, NOW.plusSeconds(60), null);
		emitters.get(1).timeout();
		assertThat(registry.activeConnectionCount()).isZero();

		registry.open(user, NOW.plusSeconds(60), null);
		emitters.get(2).fail = true;
		registry.dispatch(user, UUID.randomUUID());
		assertThat(registry.activeConnectionCount()).isZero();
	}

	@Test
	void applicationContextShutdownClosesEverySseConnection() {
		List<FakeEmitter> emitters = new ArrayList<>();
		RealtimeConnectionRegistry registry = registry(Runnable::run, properties(3, 1000), emitters);
		registry.open(UUID.randomUUID(), NOW.plusSeconds(60), null);
		registry.open(UUID.randomUUID(), NOW.plusSeconds(60), null);

		registry.onContextClosed();

		assertThat(registry.activeConnectionCount()).isZero();
	}

	@Test
	void saturatedExecutorDropsSignalWithoutClosingConnection() {
		List<FakeEmitter> emitters = new ArrayList<>();
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		Executor firstOnly = new Executor() {
			private boolean accepted;
			@Override
			public void execute(Runnable command) {
				if (accepted) throw new RejectedExecutionException("full");
				accepted = true;
				command.run();
			}
		};
		RealtimeConnectionRegistry registry = registry(firstOnly, properties(3, 1000), emitters, meters);
		UUID user = UUID.randomUUID();
		registry.open(user, NOW.plusSeconds(60), null);

		registry.dispatch(user, UUID.randomUUID());

		assertThat(registry.activeConnectionCount()).isOne();
		assertThat(meters.counter("crowdpass.realtime.signals.dropped", "reason", "executor_saturated").count())
				.isEqualTo(1);
	}

	@Test
	void redisListenerReturnsWithoutWaitingForSlowSseWrite() throws Exception {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(4));
		executors.add(executor);
		List<FakeEmitter> emitters = new ArrayList<>();
		CountDownLatch blocked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		RealtimeConnectionRegistry registry = registry(executor, properties(3, 1000), emitters);
		UUID user = UUID.randomUUID();
		registry.open(user, NOW.plusSeconds(60), null);
		await(() -> emitters.get(0).sends == 1);
		emitters.get(0).blocked = blocked;
		emitters.get(0).release = release;
		registry.dispatch(user, UUID.randomUUID());
		assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();

		RealtimeRedisListener listener = new RealtimeRedisListener(JsonMapper.builder().build(), registry,
				new SimpleMeterRegistry());
		String body = "{\"version\":1,\"kind\":\"NOTIFICATION_CREATED\",\"userId\":\"" + user
				+ "\",\"notificationId\":\"" + UUID.randomUUID() + "\"}";
		Message message = message(body);
		long started = System.nanoTime();
		listener.onMessage(message, null);

		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));
		release.countDown();
	}

	@Test
	void signalSchemaContainsNoPrivateIdentifiersOrContent() {
		RealtimeSignal signal = RealtimeSignal.notificationCreated(UUID.randomUUID(), UUID.randomUUID());
		String json = JsonMapper.builder().build().writeValueAsString(signal);

		assertThat(JsonMapper.builder().build().readTree(json).propertyNames())
				.containsExactlyInAnyOrder("version", "kind", "userId", "notificationId");
		assertThat(json).doesNotContain("email", "password", "token", "ip", "reservation", "text");
	}

	private RealtimeConnectionRegistry registry(Executor executor, RealtimeProperties properties,
			List<FakeEmitter> emitters) {
		return registry(executor, properties, emitters, new SimpleMeterRegistry());
	}

	private RealtimeConnectionRegistry registry(Executor executor, RealtimeProperties properties,
			List<FakeEmitter> emitters, SimpleMeterRegistry meters) {
		ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1,
				Thread.ofPlatform().daemon().factory());
		schedulers.add(scheduler);
		return new RealtimeConnectionRegistry(executor, scheduler, properties,
				Clock.fixed(NOW, ZoneOffset.UTC), meters, timeout -> {
					FakeEmitter emitter = new FakeEmitter(timeout);
					emitters.add(emitter);
					return emitter;
				});
	}

	private static Message message(String body) {
		return new Message() {
			@Override public byte[] getBody() { return body.getBytes(StandardCharsets.UTF_8); }
			@Override public byte[] getChannel() { return RealtimeConfiguration.CHANNEL.getBytes(StandardCharsets.UTF_8); }
		};
	}

	private static RealtimeProperties properties(int perUser, int perInstance) {
		return new RealtimeProperties(Duration.ofSeconds(20), Duration.ofMinutes(10), perUser, perInstance,
				Duration.ofSeconds(1), new RealtimeProperties.Executor(1, 1, 1));
	}

	private static void await(Check check) throws InterruptedException {
		for (int i = 0; i < 100 && !check.done(); i++) Thread.sleep(10);
		assertThat(check.done()).isTrue();
	}

	@FunctionalInterface
	private interface Check {
		boolean done();
	}

	private static final class FakeEmitter extends SseEmitter {
		private volatile int sends;
		private volatile boolean fail;
		private volatile CountDownLatch blocked;
		private volatile CountDownLatch release;
		private Runnable completion;
		private Runnable timeout;
		private Consumer<Throwable> error;

		private FakeEmitter(long timeout) {
			super(timeout);
		}

		@Override
		public void send(SseEventBuilder builder) throws IOException {
			sends++;
			if (blocked != null) {
				blocked.countDown();
				try {
					release.await();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IOException(ex);
				}
			}
			if (fail) throw new IOException("disconnected");
		}

		@Override public void onCompletion(Runnable callback) { completion = callback; }
		@Override public void onTimeout(Runnable callback) { timeout = callback; }
		@Override public void onError(Consumer<Throwable> callback) { error = callback; }
		private void completeFromClient() { completion.run(); }
		private void timeout() { timeout.run(); }
	}
}
