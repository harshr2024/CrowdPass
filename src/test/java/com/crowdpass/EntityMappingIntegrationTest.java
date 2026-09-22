package com.crowdpass;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.crowdpass.event.Event;
import com.crowdpass.event.EventStatus;
import com.crowdpass.reservation.Reservation;
import com.crowdpass.reservation.ReservationStatus;
import com.crowdpass.user.Role;
import com.crowdpass.user.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Starting this context proves Hibernate's {@code ddl-auto=validate} accepted the entity
 * mappings against the Flyway-migrated schema; the tests then exercise persistence behavior.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class EntityMappingIntegrationTest {

	private static final Instant REGISTRATION_OPEN = Instant.parse("2030-01-02T00:00:00Z");
	private static final Instant STARTS = Instant.parse("2030-01-11T02:00:00Z");
	private static final Instant REGISTRATION_CLOSE = STARTS;
	private static final Instant ENDS = STARTS.plus(3, ChronoUnit.HOURS);
	private static final ZoneId TIME_ZONE = ZoneId.of("America/Los_Angeles");

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	@Autowired
	private Environment environment;

	@Test
	void schemaValidationIsEnabledAgainstLatestMigration() {
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
		assertThat(jdbcTemplate.queryForObject(
				"select max(version::int) from flyway_schema_history where success", Integer.class))
				.isEqualTo(2);
	}

	@Test
	void persistsAndReloadsUserEventAndReservation() {
		Instant now = clock.instant();
		User organizer = persist(new User("organizer@example.com", "hash", "Olivia Organizer", Role.ORGANIZER, now));
		User attendee = persist(new User("attendee@example.com", "hash", "Adam Attendee", Role.USER, now));
		Event event = persist(newEvent(organizer, now));
		Reservation reservation = persist(new Reservation(event, attendee, now));
		entityManager.flush();
		entityManager.clear();

		User reloadedUser = entityManager.find(User.class, attendee.getId());
		assertThat(reloadedUser.getEmail()).isEqualTo("attendee@example.com");
		assertThat(reloadedUser.getDisplayName()).isEqualTo("Adam Attendee");
		assertThat(reloadedUser.getRole()).isEqualTo(Role.USER);
		assertThat(reloadedUser.getCreatedAt()).isEqualTo(now);
		assertThat(reloadedUser.getUpdatedAt()).isEqualTo(now);

		Event reloadedEvent = entityManager.find(Event.class, event.getId());
		assertThat(reloadedEvent.getOrganizer().getId()).isEqualTo(organizer.getId());
		assertThat(reloadedEvent.getName()).isEqualTo("Spring Hackathon");
		assertThat(reloadedEvent.getDescription()).isEqualTo("24 hours of building");
		assertThat(reloadedEvent.getCapacity()).isEqualTo(100);
		assertThat(reloadedEvent.getReservedCount()).isZero();
		assertThat(reloadedEvent.getStatus()).isEqualTo(EventStatus.DRAFT);
		assertThat(reloadedEvent.getTimeZone()).isEqualTo(TIME_ZONE);
		assertThat(reloadedEvent.getRegistrationOpenAt()).isEqualTo(REGISTRATION_OPEN);
		assertThat(reloadedEvent.getRegistrationCloseAt()).isEqualTo(REGISTRATION_CLOSE);
		assertThat(reloadedEvent.getStartsAt()).isEqualTo(STARTS);
		assertThat(reloadedEvent.getEndsAt()).isEqualTo(ENDS);
		assertThat(reloadedEvent.getCancelledAt()).isNull();
		assertThat(reloadedEvent.getCreatedAt()).isEqualTo(now);

		Reservation reloadedReservation = entityManager.find(Reservation.class, reservation.getId());
		assertThat(reloadedReservation.getEvent().getId()).isEqualTo(event.getId());
		assertThat(reloadedReservation.getUser().getId()).isEqualTo(attendee.getId());
		assertThat(reloadedReservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(reloadedReservation.getCreatedAt()).isEqualTo(now);
		assertThat(reloadedReservation.getCancelledAt()).isNull();
	}

	@Test
	void generatesUuidVersion7Ids() {
		Instant now = clock.instant();
		User organizer = persist(new User("v7@example.com", "hash", "V Seven", Role.ORGANIZER, now));
		Event event = persist(newEvent(organizer, now));
		Reservation reservation = persist(new Reservation(event, organizer, now));

		assertThat(List.of(organizer.getId(), event.getId(), reservation.getId()))
				.allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
	}

	@Test
	void persistsEnumsAsStrings() {
		Instant now = clock.instant();
		User organizer = persist(new User("enums@example.com", "hash", "Enum Tester", Role.ORGANIZER, now));
		Event event = persist(newEvent(organizer, now));
		Reservation reservation = persist(new Reservation(event, organizer, now));
		entityManager.flush();

		assertThat(columnValue("select role from users where id = ?", organizer.getId())).isEqualTo("ORGANIZER");
		assertThat(columnValue("select status from events where id = ?", event.getId())).isEqualTo("DRAFT");
		assertThat(columnValue("select status from reservations where id = ?", reservation.getId()))
				.isEqualTo("CONFIRMED");
	}

	@Test
	void canonicalizesEmailToSatisfyDatabaseRule() {
		User user = persist(new User("  Alice@Example.COM ", "hash", "Alice", Role.USER, clock.instant()));
		entityManager.flush();

		assertThat(columnValue("select email from users where id = ?", user.getId())).isEqualTo("alice@example.com");
	}

	@Test
	void reservedCountIsExcludedFromInsertAndUpdate() {
		EntityPersister persister = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
				.getMappingMetamodel()
				.getEntityDescriptor(Event.class);
		int index = Arrays.asList(persister.getPropertyNames()).indexOf("reservedCount");

		assertThat(index).isNotNegative();
		assertThat(persister.getPropertyInsertability()[index]).isFalse();
		assertThat(persister.getPropertyUpdateability()[index]).isFalse();
	}

	@Test
	void staleEventCannotOverwriteDatabaseReservedCount() {
		Instant now = clock.instant();
		User organizer = persist(new User("stale@example.com", "hash", "Stale Tester", Role.ORGANIZER, now));
		UUID eventId = persist(newEvent(organizer, now)).getId();
		entityManager.flush();
		entityManager.clear();

		Event stale = entityManager.find(Event.class, eventId);
		entityManager.detach(stale);
		jdbcTemplate.update("update events set reserved_count = 7 where id = ?", eventId);

		entityManager.merge(stale);
		entityManager.flush();
		entityManager.clear();

		assertThat(stale.getReservedCount()).isZero();
		assertThat(entityManager.find(Event.class, eventId).getReservedCount()).isEqualTo(7);
	}

	@Test
	void associationsAreLazy() {
		Instant now = clock.instant();
		User organizer = persist(new User("lazy@example.com", "hash", "Lazy Tester", Role.ORGANIZER, now));
		Event event = persist(newEvent(organizer, now));
		Reservation reservation = persist(new Reservation(event, organizer, now));
		entityManager.flush();
		entityManager.clear();

		Reservation reloadedReservation = entityManager.find(Reservation.class, reservation.getId());
		assertThat(Hibernate.isInitialized(reloadedReservation.getEvent())).isFalse();
		assertThat(Hibernate.isInitialized(reloadedReservation.getUser())).isFalse();

		entityManager.clear();
		Event reloadedEvent = entityManager.find(Event.class, event.getId());
		assertThat(Hibernate.isInitialized(reloadedEvent.getOrganizer())).isFalse();
	}

	@Test
	void clockMatchesPostgresMicrosecondPrecision() {
		assertThat(clock.instant().getNano() % 1_000).isZero();
		assertThat(clock.getZone()).isEqualTo(java.time.ZoneOffset.UTC);
	}

	private Event newEvent(User organizer, Instant now) {
		return new Event(organizer, "Spring Hackathon", "24 hours of building", 100, TIME_ZONE,
				REGISTRATION_OPEN, REGISTRATION_CLOSE, STARTS, ENDS, now);
	}

	private <T> T persist(T entity) {
		entityManager.persist(entity);
		return entity;
	}

	private String columnValue(String sql, UUID id) {
		return jdbcTemplate.queryForObject(sql, String.class, id);
	}

}
