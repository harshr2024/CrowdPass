# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/maven-wrapper.properties
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Dmaven.test.skip=true dependency:go-offline

COPY src/main src/main
ARG SOURCE_DATE_EPOCH=315532800
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Dmaven.test.skip=true \
      -Dproject.build.outputTimestamp="${SOURCE_DATE_EPOCH}" package && \
    cp target/crowdpass-api-*.jar application.jar && \
    java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-noble AS runtime

ARG APP_VERSION=dev
ARG VCS_REVISION=unknown
LABEL org.opencontainers.image.title="CrowdPass API" \
      org.opencontainers.image.description="High-concurrency event reservation API" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REVISION}"

RUN command -v curl >/dev/null && \
    groupadd --system --gid 10001 crowdpass && \
    useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent \
      --shell /usr/sbin/nologin crowdpass

WORKDIR /app
COPY --from=build --chown=root:root /workspace/extracted/dependencies/ ./
COPY --from=build --chown=root:root /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=root:root /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=root:root /workspace/extracted/application/ ./
RUN chmod -R a-w /app && chmod -R a+rX /app

USER 10001:10001
EXPOSE 8080

# Overridable as one standard JVM option variable at deployment time. Phase 13 owns tuning.
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=70.0"

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD ["curl", "--fail", "--silent", "--show-error", "--max-time", "2", "http://127.0.0.1:8080/readyz"]

ENTRYPOINT ["java", "-jar", "application.jar"]
