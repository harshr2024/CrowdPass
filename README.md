# CrowdPass

High-concurrency event reservation and virtual queue platform.

> Status: Phase 6 baseline. CrowdPass includes a Spring Boot and PostgreSQL foundation,
> authentication and authorization, concurrency-safe reservations, transactional FIFO waitlist
> promotion, Redis distributed rate limiting, a transactional outbox, Standard SQS-compatible
> asynchronous notifications, and idempotent notification consumption. The current baseline has
> 419 passing tests.

## Local Setup

### Prerequisites

- JDK 21 (Temurin). If multiple JDKs are installed, point Maven at 21:
  `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
- Docker (Docker Desktop on macOS), used for local PostgreSQL and for Testcontainers.

Maven does not need to be installed; use the wrapper `./mvnw`.

### Run

```bash
docker compose up -d                              # PostgreSQL 17 on localhost:5432
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
curl localhost:8080/actuator/health
```

The `local` profile shows health details (database component, etc.). Without it, health returns only the overall status.

Configuration comes from environment variables with local defaults:

| Variable      | Default                                     |
|---------------|---------------------------------------------|
| `DB_URL`      | `jdbc:postgresql://localhost:5432/crowdpass` |
| `DB_USERNAME` | `crowdpass`                                 |
| `DB_PASSWORD` | `crowdpass`                                 |

To change the Compose database credentials or port, copy `.env.example` to `.env`.

### Test

```bash
./mvnw test
```

Integration tests start their own disposable PostgreSQL 17 container via Testcontainers; they do not use the Compose database.

### Reset the local database

```bash
docker compose down -v   # removes the crowdpass-pgdata volume
```

## Schema Management

Flyway owns all schema changes (`src/main/resources/db/migration`). Hibernate runs with `ddl-auto=validate` and never modifies the schema.
