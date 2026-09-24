package com.crowdpass.realtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealtimeProperties.class)
class RealtimeConfiguration {

	static final String CHANNEL = "crowdpass:realtime:v1:notifications";

	@Bean(name = "realtimeDeliveryExecutor", destroyMethod = "shutdown")
	Executor realtimeDeliveryExecutor(RealtimeProperties properties) {
		RealtimeProperties.Executor settings = properties.executor();
		return new ThreadPoolExecutor(settings.coreSize(), settings.maxSize(), 30, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(settings.queueCapacity()),
				Thread.ofPlatform().name("realtime-delivery-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
	}

	@Bean(name = "realtimeScheduler", destroyMethod = "shutdownNow")
	ScheduledExecutorService realtimeScheduler() {
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
				Thread.ofPlatform().name("realtime-scheduler-", 0).daemon().factory());
		executor.setRemoveOnCancelPolicy(true);
		executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		return executor;
	}

	@Bean
	ChannelTopic realtimeTopic() {
		return new ChannelTopic(CHANNEL);
	}

	@Bean
	RedisMessageListenerContainer realtimeRedisContainer(RedisConnectionFactory connectionFactory,
			RealtimeRedisListener listener, ChannelTopic realtimeTopic, RealtimeProperties properties) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.setAutoStartup(false);
		container.setRecoveryInterval(properties.redisRecoveryInterval().toMillis());
		container.addMessageListener(listener, realtimeTopic);
		return container;
	}
}
