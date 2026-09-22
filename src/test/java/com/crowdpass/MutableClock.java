package com.crowdpass;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test clock that can be moved and that counts how often it is read. */
public class MutableClock extends Clock {

	private final AtomicReference<Instant> now;
	private final AtomicInteger reads = new AtomicInteger();

	public MutableClock(Instant start) {
		this.now = new AtomicReference<>(start);
	}

	public void set(Instant instant) {
		now.set(instant);
	}

	public void advance(Duration duration) {
		now.updateAndGet(current -> current.plus(duration));
	}

	public int resetReads() {
		return reads.getAndSet(0);
	}

	public int reads() {
		return reads.get();
	}

	@Override
	public Instant instant() {
		reads.incrementAndGet();
		return now.get();
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException();
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		public static final Instant START = Instant.parse("2031-03-01T12:00:00Z");

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(START);
		}

	}

}
