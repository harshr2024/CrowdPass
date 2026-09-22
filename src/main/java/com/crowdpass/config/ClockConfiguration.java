package com.crowdpass.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

	/**
	 * UTC clock truncated to microseconds, the precision of PostgreSQL {@code timestamptz}, so an
	 * {@code Instant} compares equal before and after a database round trip.
	 */
	@Bean
	public Clock clock() {
		return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
	}

}
