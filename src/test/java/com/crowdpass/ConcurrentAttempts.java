package com.crowdpass;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/**
 * Runs attempts from a bounded pool of worker threads. All workers wait on one start gate, then
 * keep pulling attempts until none remain, so contention is sustained for the whole run.
 *
 * <p>Each attempt returns an outcome label; any exception it throws is recorded as unexpected.
 */
public final class ConcurrentAttempts {

	private ConcurrentAttempts() {
	}

	public record Result(Map<String, Long> outcomes, List<Throwable> unexpected, boolean finished,
			int peakInFlight, int peakAwaitingConnection) {

		public long count(String outcome) {
			return outcomes.getOrDefault(outcome, 0L);
		}

	}

	public static Result run(int workers, List<Callable<String>> attempts, IntSupplier threadsAwaitingConnection)
			throws InterruptedException {
		Queue<Callable<String>> queue = new ConcurrentLinkedQueue<>(attempts);
		Map<String, LongAdder> outcomes = new ConcurrentHashMap<>();
		Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger peakInFlight = new AtomicInteger();
		AtomicInteger peakAwaiting = new AtomicInteger();
		CountDownLatch ready = new CountDownLatch(workers);
		CountDownLatch start = new CountDownLatch(1);

		ExecutorService executor = Executors.newFixedThreadPool(workers);
		try {
			for (int i = 0; i < workers; i++) {
				executor.execute(() -> {
					ready.countDown();
					try {
						start.await();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						return;
					}
					Callable<String> attempt;
					while ((attempt = queue.poll()) != null) {
						peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
						peakAwaiting.accumulateAndGet(threadsAwaitingConnection.getAsInt(), Math::max);
						try {
							outcomes.computeIfAbsent(attempt.call(), key -> new LongAdder()).increment();
						}
						catch (Throwable ex) {
							unexpected.add(ex);
						}
						finally {
							inFlight.decrementAndGet();
						}
					}
				});
			}
			if (!ready.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Workers did not start");
			}
			start.countDown();
		}
		finally {
			executor.shutdown();
		}
		boolean finished = executor.awaitTermination(120, TimeUnit.SECONDS);

		Map<String, Long> totals = new TreeMap<>();
		outcomes.forEach((outcome, count) -> totals.put(outcome, count.sum()));
		return new Result(totals, List.copyOf(unexpected), finished, peakInFlight.get(), peakAwaiting.get());
	}

}
