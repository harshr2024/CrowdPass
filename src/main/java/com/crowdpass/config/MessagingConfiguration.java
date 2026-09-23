package com.crowdpass.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
@EnableScheduling
public class MessagingConfiguration {

	/**
	 * Every call is bounded by explicit timeouts. Long-poll receives override the per-call timeouts
	 * on the request itself. Socket timeout exceeds the maximum 20s long poll.
	 */
	@Bean(destroyMethod = "close")
	SqsClient sqsClient(MessagingProperties properties) {
		MessagingProperties.Sqs sqs = properties.sqs();
		SqsClientBuilder builder = SqsClient.builder()
				.region(Region.of(sqs.region()))
				.credentialsProvider(credentials(sqs))
				.httpClientBuilder(Apache5HttpClient.builder()
						.connectionTimeout(Duration.ofSeconds(2))
						.socketTimeout(Duration.ofSeconds(30)))
				.overrideConfiguration(config -> config
						.apiCallTimeout(sqs.apiCallTimeout())
						.apiCallAttemptTimeout(sqs.apiCallAttemptTimeout()));
		if (hasText(sqs.endpoint())) {
			builder.endpointOverride(URI.create(sqs.endpoint()));
		}
		return builder.build();
	}

	/** Static credentials only for an SQS-compatible local/test endpoint; AWS uses the default chain. */
	private static AwsCredentialsProvider credentials(MessagingProperties.Sqs sqs) {
		if (hasText(sqs.endpoint()) && hasText(sqs.accessKeyId())) {
			return StaticCredentialsProvider.create(AwsBasicCredentials.create(sqs.accessKeyId(), sqs.secretAccessKey()));
		}
		return DefaultCredentialsProvider.builder().build();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}
