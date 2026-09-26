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
import com.crowdpass.event.LockedEvent;
import com.crowdpass.exception.ApiException;
import com.crowdpass.outbox.OutboxWriter;
import com.crowdpass.reservation.ReservationExceptions.AlreadyReserved;
import com.crowdpass.reservation.ReservationExceptions.CancellationClosed;
import com.crowdpass.reservation.ReservationExceptions.EventFull;
import com.crowdpass.reservation.ReservationExceptions.RegistrationClosed;
import com.crowdpass.reservation.ReservationExceptions.RegistrationNotOpen;
import com.crowdpass.reservation.ReservationExceptions.ReservationNotFound;
import com.crowdpass.reservation.waitlist.WaitlistEntry;
import com.crowdpass.reservation.waitlist.WaitlistEntryRepository;
import com.crowdpass.reservation.waitlist.WaitlistStatus;
import com.crowdpass.user.AuthenticatedUserNotFoundException;
import com.crowdpass.user.UserRepository;

import io.micrometer.core.instrument.Timer;

/**
 * Owns the seat invariants for each event:
 * <ul>
 * <li>{@code reserved_count} equals the number of CONFIRMED reservations and never exceeds capacity;</li>
 * <li>a user holds at most one CONFIRMED reservation and at most one WAITING entry, never both;</li>
 * <li>while any entry is WAITING, the event is full, so a freed seat always goes to the head of the
 * waitlist rather than to a new direct reservation.</li>
 * </ul>
 *
 * <p>Rules every seat- or waitlist-changing transaction must follow:
 * <ul>
 * <li><b>Event row first.</b> Lock the event row ({@link EventRepository#tryAcquireSeat} or
 * {@link EventRepository#lockForSeatChange}) before modifying any reservation or waitlist entry of
 * that event. A different order can deadlock, and waitlist ordering depends on joins being
 * serialized by this lock.</li>
 * <li><b>Freed seats go to the waitlist first.</b> Any operation that frees a seat, including a future
 * organizer capacity increase, must call {@link #fillFreedSeatOrRelease} while holding the lock.</li>
 * <li>Change {@code reserved_count} only through the conditional UPDATE methods, in the same
 * transaction as the row changes. Never base a decision on {@code Event.getReservedCount()}.</li>
 * <li>Keep PostgreSQL's default READ COMMITTED isolation.</li>
 * <li>Capture the current time once per operation.</li>
 * </ul>
 */
@Service
public class ReservationService {

	private static final String ACTIVE_RESERVATION_UNIQUE_INDEX = "ux_reservations_active_user_event";
	private static final String RESERVATION_USER_FOREIGN_KEY = "fk_reservations_user";

	private final ReservationRepository reservationRepository;
	private final WaitlistEntryRepository waitlistEntryRepository;
	private final EventRepository eventRepository;
	private final UserRepository userRepository;
	private final OutboxWriter outboxWriter;
	private final Clock clock;
	private final ReservationMetrics metrics;

	public ReservationService(ReservationRepository reservationRepository,
			WaitlistEntryRepository waitlistEntryRepository, EventRepository eventRepository,
			UserRepository userRepository, OutboxWriter outboxWriter, Clock clock, ReservationMetrics metrics) {
		this.reservationRepository = reservationRepository;
		this.waitlistEntryRepository = waitlistEntryRepository;
		this.eventRepository = eventRepository;
		this.userRepository = userRepository;
		this.outboxWriter = outboxWriter;
		this.clock = clock;
		this.metrics = metrics;
	}

	/**
	 * Claims a seat and records the reservation in one transaction. If the INSERT fails, the
	 * rollback also undoes the seat claim.
	 */
	@Transactional
	public ReservationResponse reserve(UUID eventId, UUID userId) {
		Timer.Sample sample = metrics.start();
		try {
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
			metrics.reservationCommitted(sample, "reserve", "confirmed");
			return ReservationResponse.from(reservation);
		}
		catch (ApiException ex) {
			metrics.reservationRejected(sample, "reserve", ex);
			throw ex;
		}
		catch (RuntimeException ex) {
			metrics.unexpected(sample, "crowdpass.reservations.operations", "reserve", ex);
			throw ex;
		}
	}

	/**
	 * Cancels the caller's reservation. In the same transaction, the freed seat goes to the head of
	 * the waitlist (count unchanged) or, if nobody is waiting, is released (count decremented once).
	 * Cancelling an already cancelled reservation returns its current state and changes nothing.
	 */
	@Transactional
	public ReservationResponse cancel(UUID reservationId, UUID userId) {
		Timer.Sample sample = metrics.start();
		try {
			Instant now = clock.instant();
			Reservation owned = reservationRepository.findByIdAndUserId(reservationId, userId)
					.orElseThrow(ReservationNotFound::new);
			UUID eventId = owned.getEvent().getId();

			LockedEvent event = eventRepository.lockForSeatChange(eventId)
					.orElseThrow(() -> new IllegalStateException("Reservation " + reservationId + " has no event"));
			boolean cancelled = reservationRepository.cancelIfConfirmed(reservationId, now) == 1;
			if (cancelled) {
				if (!now.isBefore(event.getStartsAt())) {
					throw new CancellationClosed();
				}
				fillFreedSeatOrRelease(eventId, event, now);
			}
			metrics.reservationCommitted(sample, "cancel", cancelled ? "cancelled" : "already_cancelled");
			return reservationRepository.findById(reservationId)
					.map(ReservationResponse::from)
					.orElseThrow(ReservationNotFound::new);
		}
		catch (ApiException ex) {
			metrics.reservationRejected(sample, "cancel", ex);
			throw ex;
		}
		catch (RuntimeException ex) {
			metrics.unexpected(sample, "crowdpass.reservations.operations", "cancel", ex);
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	public ReservationResponse getReservation(UUID reservationId, UUID userId) {
		return reservationRepository.findByIdAndUserId(reservationId, userId)
				.map(ReservationResponse::from)
				.orElseThrow(ReservationNotFound::new);
	}

	/**
	 * Hands one freed seat to the head of the waitlist, or releases it if nobody is waiting (or the
	 * event is no longer published). The caller must hold the event row lock. Any inconsistency
	 * fails the whole transaction rather than skipping a waiting user.
	 *
	 * <p>A successful promotion also appends one {@code WAITLIST_PROMOTED} outbox row in this same
	 * transaction. The notification itself is delivered later; this method never calls SQS. If
	 * nobody is waiting, no outbox row is written.
	 */
	private void fillFreedSeatOrRelease(UUID eventId, LockedEvent event, Instant now) {
		Optional<WaitlistEntry> head = event.isPublished()
				? waitlistEntryRepository.findFirstByEventIdAndStatusOrderByQueueSeqAsc(eventId, WaitlistStatus.WAITING)
				: Optional.empty();
		if (head.isEmpty()) {
			if (eventRepository.releaseSeat(eventId) != 1) {
				throw new IllegalStateException("reserved_count drift detected for event " + eventId);
			}
			return;
		}

		WaitlistEntry entry = head.get();
		Reservation promoted = new Reservation(eventRepository.getReferenceById(eventId),
				userRepository.getReferenceById(entry.getUser().getId()), now);
		try {
			reservationRepository.saveAndFlush(promoted);
		}
		catch (DataIntegrityViolationException ex) {
			throw new IllegalStateException("Cannot promote waitlist entry " + entry.getId()
					+ ": the waiting user already holds a reservation for event " + eventId, ex);
		}
		if (waitlistEntryRepository.markPromoted(entry.getId(), promoted.getId(), now) != 1) {
			throw new IllegalStateException("Waitlist entry " + entry.getId() + " was not WAITING during promotion");
		}
		outboxWriter.append(WaitlistPromotedEvent.TYPE, WaitlistPromotedEvent.VERSION,
				WaitlistPromotedEvent.AGGREGATE_TYPE, entry.getId(),
				new WaitlistPromotedEvent(entry.getUser().getId(), eventId, promoted.getId(), entry.getId()), now);
		metrics.promotionCommitted();
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
