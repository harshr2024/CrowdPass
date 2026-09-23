package com.crowdpass.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param sqs connection settings; a blank {@code endpoint} means real AWS with the default
 *     credential chain, a non-blank one means an SQS-compatible local/test service
 * @param publisher outbox publisher settings
 * @param consumer notification consumer settings
 */
@ConfigurationProperties("crowdpass.messaging")
public record MessagingProperties(Sqs sqs, Publisher publisher, Consumer consumer) {

	public record Sqs(String endpoint, String region, String queueName, String accessKeyId, String secretAccessKey,
			Duration apiCallTimeout, Duration apiCallAttemptTimeout) {
	}

	public record Publisher(boolean enabled, int batchSize) {
	}

	/** {@code waitTime} is the long-poll duration; must stay well below the queue visibility timeout. */
	public record Consumer(boolean enabled, Duration waitTime, int maxMessages) {
	}

}
