package com.crowdpass.idempotency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crowdpass.idempotency")
public record IdempotencyProperties(Duration retention, Cleanup cleanup) {

	public record Cleanup(Duration fixedDelay, int batchSize) {
	}

}
