package com.crowdpass.config;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.sqs.SqsClient;

/** Resolves the notification queue URL on first use, so startup never depends on SQS. */
@Component
public class NotificationQueue {

	private final SqsClient sqsClient;
	private final String queueName;
	private final AtomicReference<String> url = new AtomicReference<>();

	public NotificationQueue(SqsClient sqsClient, MessagingProperties properties) {
		this.sqsClient = sqsClient;
		this.queueName = properties.sqs().queueName();
	}

	public String url() {
		String resolved = url.get();
		if (resolved == null) {
			resolved = sqsClient.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
			url.compareAndSet(null, resolved);
		}
		return resolved;
	}

}
