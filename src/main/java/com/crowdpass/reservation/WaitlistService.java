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
import com.crowdpass.reservation.ReservationExceptions.AlreadyReserved;
import com.crowdpass.reservation.ReservationExceptions.RegistrationClosed;
import com.crowdpass.reservation.ReservationExceptions.RegistrationNotOpen;
import com.crowdpass.reservation.WaitlistExceptions.AlreadyPromoted;
import com.crowdpass.reservation.WaitlistExceptions.AlreadyWaitlisted;
import com.crowdpass.reservation.WaitlistExceptions.SeatAvailable;
import com.crowdpass.reservation.WaitlistExceptions.WaitlistEntryNotFound;
import com.crowdpass.reservation.waitlist.WaitlistEntry;
import com.crowdpass.reservation.waitlist.WaitlistEntryRepository;
import com.crowdpass.reservation.waitlist.WaitlistStatus;
import com.crowdpass.user.AuthenticatedUserNotFoundException;
import com.crowdpass.user.UserRepository;

/**
 * Joining, leaving, and reading the waitlist. Follows the rules documented on
 * {@link ReservationService}; in particular, joins and leaves lock the event row first.
 *
 * <p>Fairness: promotion is FIFO by the database-assigned {@code queue_seq} of committed joins.
 * Because each join holds the event row lock from before its INSERT until commit, queue_seq order
 * equals commit order for an event, and no lower queue_seq can appear after a promoter looks.
 */
@Service
public class WaitlistService {

	private static final String WAITING_UNIQUE_INDEX = "ux_waitlist_entries_waiting_user_event";
	private static final String WAITLIST_USER_FOREIGN_KEY = "fk_waitlist_entries_user";

	private final WaitlistEntryRepository waitlistEntryRepository;
	private final ReservationRepository reservationRepository;
	private final EventRepository eventRepository;
	private final UserRepository userRepository;
	private final Clock clock;

	public WaitlistService(WaitlistEntryRepository waitlistEntryRepository,
			ReservationRepository reservationRepository, EventRepository eventRepository,
			UserRepository userRepository, Clock clock) {
		this.waitlistEntryRepository = waitlistEntryRepository;
		this.reservationRepository = reservationRepository;
		this.eventRepository = eventRepository;
		this.userRepository = userRepository;
		this.clock = clock;
	}

	/** Joins only a published, currently full event whose registration is open. */
	@Transactional
	public WaitlistEntryResponse join(UUID eventId, UUID userId) {
		Instant now = clock.instant();

		LockedEvent event = eventRepository.lockForSeatChange(eventId).orElseThrow(EventNotFoundException::new);
		if (!event.isPublished()) {
			throw new EventNotFoundException();
		}
		if (now.isBefore(event.getRegistrationOpenAt())) {
			throw new RegistrationNotOpen();
		}
		if (!now.isBefore(event.getRegistrationCloseAt())) {
			throw new RegistrationClosed();
		}
		if (reservationRepository.existsByEventIdAndUserIdAndStatus(eventId, userId, ReservationStatus.CONFIRMED)) {
			throw new AlreadyReserved();
		}
		if (waitlistEntryRepository.existsByEventIdAndUserIdAndStatus(eventId, userId, WaitlistStatus.WAITING)) {
			throw new AlreadyWaitlisted();
		}
		if (event.getReservedCount() < event.getCapacity()) {
			throw new SeatAvailable();
		}

		WaitlistEntry entry = new WaitlistEntry(eventRepository.getReferenceById(eventId),
				userRepository.getReferenceById(userId), now);
		try {
			waitlistEntryRepository.saveAndFlush(entry);
		}
		catch (DataIntegrityViolationException ex) {
			Optional<String> constraint = DatabaseConstraints.violatedConstraint(ex);
			if (constraint.filter(WAITING_UNIQUE_INDEX::equals).isPresent()) {
				throw new AlreadyWaitlisted();
			}
			if (constraint.filter(WAITLIST_USER_FOREIGN_KEY::equals).isPresent()) {
				throw new AuthenticatedUserNotFoundException();
			}
			throw ex;
		}
		return toResponse(entry, false);
	}

	/**
	 * Leaves the waitlist. Repeating after leaving returns the existing entry; a promoted user must
	 * cancel the reservation instead.
	 */
	@Transactional
	public WaitlistEntryResponse leave(UUID eventId, UUID userId) {
		Instant now = clock.instant();

		LockedEvent event = eventRepository.lockForSeatChange(eventId).orElseThrow(WaitlistEntryNotFound::new);
		int left = waitlistEntryRepository.leaveIfWaiting(eventId, userId, now);
		WaitlistEntry latest = waitlistEntryRepository.findFirstByEventIdAndUserIdOrderByQueueSeqDesc(eventId, userId)
				.orElseThrow(WaitlistEntryNotFound::new);
		if (left == 0 && latest.getStatus() == WaitlistStatus.PROMOTED) {
			throw new AlreadyPromoted();
		}
		if (latest.getStatus() != WaitlistStatus.LEFT) {
			throw new IllegalStateException("Waitlist entry " + latest.getId() + " is " + latest.getStatus()
					+ " after leave");
		}
		return toResponse(latest, isClosed(event.isPublished(), event.getStartsAt(), now));
	}

	/** The caller's latest entry for the event. The position is a snapshot. */
	@Transactional(readOnly = true)
	public WaitlistEntryResponse getMyEntry(UUID eventId, UUID userId) {
		Instant now = clock.instant();

		WaitlistEntry entry = waitlistEntryRepository.findFirstByEventIdAndUserIdOrderByQueueSeqDesc(eventId, userId)
				.orElseThrow(WaitlistEntryNotFound::new);
		Event event = eventRepository.findById(eventId).orElseThrow(WaitlistEntryNotFound::new);
		return toResponse(entry, isClosed(event.getStatus() == EventStatus.PUBLISHED, event.getStartsAt(), now));
	}

	private WaitlistEntryResponse toResponse(WaitlistEntry entry, boolean waitlistClosed) {
		Long position = entry.getStatus() == WaitlistStatus.WAITING
				? waitlistEntryRepository.countWaitingAhead(entry.getEvent().getId(), entry.getQueueSeq()) + 1
				: null;
		return WaitlistEntryResponse.from(entry, position, waitlistClosed);
	}

	private static boolean isClosed(boolean published, Instant startsAt, Instant now) {
		return !published || !now.isBefore(startsAt);
	}

}
