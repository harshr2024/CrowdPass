package com.crowdpass.realtime;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crowdpass.realtime")
public record RealtimeProperties(Duration heartbeat, Duration maxStreamLifetime, int maxConnectionsPerUser,
		int maxConnectionsPerInstance, Duration redisRecoveryInterval, Executor executor) {

	public record Executor(int coreSize, int maxSize, int queueCapacity) {
	}
}
