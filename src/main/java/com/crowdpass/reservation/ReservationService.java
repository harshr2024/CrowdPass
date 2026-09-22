package com.crowdpass.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crowdpass.common.DatabaseConstraints;
import com.crowdpass.event.Event;
import com.crowdpass.event.EventNotFoundException;
import com.crowdpass.event.EventRepository;
import com.crowdpass.event.EventStatus;
import com.crowdpass.exception.ApiException;
import com.crowdpass.reservation.ReservationExceptions.AlreadyReserved;
import com.crowdpass.reservation.ReservationExceptions.CancellationClosed;
import com.crowdpass.reservation.ReservationExceptions.EventFull;
import com.crowdpass.reservation.ReservationExceptions.RegistrationClosed;
import com.crowdpass.reservation.ReservationExceptions.RegistrationNotOpen;
import com.crowdpass.reservation.ReservationExceptions.ReservationNotFound;
import com.crowdpass.user.AuthenticatedUserNotFoundException;
import com.crowdpass.user.UserRepository;

/**
 * Owns the capacity invariant: {@code events.reserved_count} equals the number of CONFIRMED
 * reservations and never exceeds capacity, and a user holds at most one CONFIRMED reservation per
 * event.
 *
 * <p>Rules every reservation-changing transaction must follow (including future waitlist promotion):
 * <ul>
 * <li><b>Event row first.</b> Lock the event row (via {@link EventRepository#tryAcquireSeat} or
 * {@link EventRepository#lockForReservationChange}) before modifying any of its reservations.
 * Taking locks in a different order can deadlock against concurrent reserve and cancel.</li>
 * <li>Change {@code reserved_count} only through the conditional UPDATE methods, in the same
 * transaction as the reservation row change. Never base a decision on
 * {@code Event.getReservedCount()}, which is a stale snapshot.</li>
 * <li>Keep PostgreSQL's default READ COMMITTED isolation; the conditional UPDATE relies on its
 * re-evaluation of the WHERE clause after a lock wait.</li>
 * <li>Capture the current time once per operation and use it for every time-based decision.</li>
 * </ul>
 */
@Service
public class ReservationService {

	private static final String ACTIVE_RESERVATION_UNIQUE_INDEX = "ux_reservations_active_user_event";
	private static final String RESERVATION_USER_FOREIGN_KEY = "fk_reservations_user";

	private final ReservationRepository reservationRepository;
	private final EventRepository eventRepository;
	private final UserRepository userRepository;
	private final Clock clock;

	public ReservationService(ReservationRepository reservationRepository, EventRepository eventRepository,
			UserRepository userRepository, Clock clock) {
		this.reservationRepository = reservationRepository;
		this.eventRepository = eventRepository;
		this.userRepository = userRepository;
		this.clock = clock;
	}

	/**
	 * Claims a seat and records the reservation in one transaction. If the INSERT fails, the
	 * rollback also undoes the seat claim.
	 */
	@Transactional
	public ReservationResponse reserve(UUID eventId, UUID userId) {
		Instant now = clock.instant();

		if (eventRepository.tryAcquireSeat(eventId, now) == 0) {
			throw explainUnavailable(eventId, userId, now);
		}

		Reservation reservation = new Reservation(eventRepository.getReferenceById(eventId),
				userRepository.getReferenceById(userId), now);
		try {
			reservationRepository.saveAndFlush(reservation);
		}
		catch (DataIntegrityViolationException ex) {
			// The transaction is aborted at this point: decide from the exception alone.
			Optional<String> constraint = DatabaseConstraints.violatedConstraint(ex);
			if (constraint.filter(ACTIVE_RESERVATION_UNIQUE_INDEX::equals).isPresent()) {
				throw new AlreadyReserved();
			}
			if (constraint.filter(RESERVATION_USER_FOREIGN_KEY::equals).isPresent()) {
				throw new AuthenticatedUserNotFoundException();
			}
			throw ex;
		}
		return ReservationResponse.from(reservation);
	}

	/**
	 * Cancels the caller's reservation and releases its seat exactly once. Cancelling an already
	 * cancelled reservation returns its current state without releasing another seat.
	 */
	@Transactional
	public ReservationResponse cancel(UUID reservationId, UUID userId) {
		Instant now = clock.instant();

		Reservation owned = reservationRepository.findByIdAndUserId(reservationId, userId)
				.orElseThrow(ReservationNotFound::new);
		UUID eventId = owned.getEvent().getId();

		Instant startsAt = eventRepository.lockForReservationChange(eventId);
		if (reservationRepository.cancelIfConfirmed(reservationId, now) == 1) {
			if (!now.isBefore(startsAt)) {
				throw new CancellationClosed();
			}
			if (eventRepository.releaseSeat(eventId) != 1) {
				throw new IllegalStateException("reserved_count drift detected for event " + eventId);
			}
		}
		return reservationRepository.findById(reservationId)
				.map(ReservationResponse::from)
				.orElseThrow(ReservationNotFound::new);
	}

	@Transactional(readOnly = true)
	public ReservationResponse getReservation(UUID reservationId, UUID userId) {
		return reservationRepository.findByIdAndUserId(reservationId, userId)
				.map(ReservationResponse::from)
				.orElseThrow(ReservationNotFound::new);
	}

	/**
	 * Explains why the atomic seat claim matched no row. These reads are not locked and may observe
	 * newer state; anything not otherwise explained is reported as full.
	 */
	private ApiException explainUnavailable(UUID eventId, UUID userId, Instant now) {
		Optional<Event> event = eventRepository.findById(eventId);
		if (event.isEmpty() || event.get().getStatus() != EventStatus.PUBLISHED) {
			return new EventNotFoundException();
		}
		if (now.isBefore(event.get().getRegistrationOpenAt())) {
			return new RegistrationNotOpen();
		}
		if (!now.isBefore(event.get().getRegistrationCloseAt())) {
			return new RegistrationClosed();
		}
		if (reservationRepository.existsByEventIdAndUserIdAndStatus(eventId, userId, ReservationStatus.CONFIRMED)) {
			return new AlreadyReserved();
		}
		return new EventFull();
	}

}
