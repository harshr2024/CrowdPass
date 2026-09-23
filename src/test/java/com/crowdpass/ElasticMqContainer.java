package com.crowdpass;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** JVM ElasticMQ, shared by the messaging tests. Not the native image. */
final class ElasticMqContainer {

	static final GenericContainer<?> CONTAINER = new GenericContainer<>(
			DockerImageName.parse("softwaremill/elasticmq:1.7.1"))
			.withCopyFileToContainer(MountableFile.forClasspathResource("elasticmq.conf"), "/opt/elasticmq.conf")
			.withExposedPorts(9324)
			.withStartupTimeout(Duration.ofMinutes(3));

	static {
		CONTAINER.start();
	}

	private ElasticMqContainer() {
	}

	static String endpoint() {
		return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(9324);
	}

}
