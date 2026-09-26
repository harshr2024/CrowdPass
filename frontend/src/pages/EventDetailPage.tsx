import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, messageFor } from "../api/client";
import {
  ReservationAttempt,
  reservationFailureMessage,
} from "../api/idempotency";
import { queryKeys } from "../api/queryKeys";
import { useAuth } from "../auth/AuthProvider";
import { Button } from "../components/Button";
import { ErrorState, InlineNotice, PageLoader } from "../components/Feedback";
import { StatusBadge } from "../components/StatusBadge";
import { formatEventDate, formatEventTime } from "../lib/format";

export function EventDetailPage() {
  const { id = "" } = useParams();
  const auth = useAuth();
  const queryClient = useQueryClient();
  const attempt = useRef(new ReservationAttempt());
  const [actionMessage, setActionMessage] = useState<string | null>(null);
  const [renderedAt] = useState(() => Date.now());
  const event = useQuery({
    queryKey: queryKeys.event(id),
    queryFn: () => api.event(id),
    enabled: Boolean(id),
  });
  const activeReservation = useQuery({
    queryKey: queryKeys.activeReservation(id),
    queryFn: () => api.activeReservation(id),
    enabled: Boolean(auth.token && id),
    retry: false,
  });
  const waitlist = useQuery({
    queryKey: queryKeys.waitlist(id),
    queryFn: () => api.waitlist(id),
    enabled: Boolean(auth.token && id),
    retry: false,
  });
  const refreshState = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.event(id) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.reservations }),
      queryClient.invalidateQueries({
        queryKey: queryKeys.activeReservation(id),
      }),
      queryClient.invalidateQueries({ queryKey: queryKeys.waitlist(id) }),
    ]);
  };

  const reserve = useMutation({
    mutationFn: () => api.reserve(id, attempt.current.currentKey()),
    onSuccess: async () => {
      attempt.current.finish();
      setActionMessage("Your seat is confirmed.");
      await refreshState();
    },
    onError: async (error) => {
      attempt.current.recordFailure(error);
      setActionMessage(reservationFailureMessage(error) ?? messageFor(error));
      await refreshState();
    },
  });
  const cancel = useMutation({
    mutationFn: () => api.cancelReservation(activeReservation.data?.id ?? ""),
    onSuccess: async () => {
      setActionMessage(
        "Reservation cancelled. If someone was waiting, they were promoted immediately.",
      );
      await refreshState();
    },
  });
  const join = useMutation({
    mutationFn: () => api.joinWaitlist(id),
    onSuccess: async () => {
      setActionMessage(
        "You are on the waitlist. We will notify you if you are promoted.",
      );
      await refreshState();
    },
  });
  const leave = useMutation({
    mutationFn: () => api.leaveWaitlist(id),
    onSuccess: async () => {
      setActionMessage("You have left the waitlist.");
      await refreshState();
    },
  });

  if (event.isLoading) return <PageLoader label="Opening event" />;
  if (event.isError || !event.data)
    return (
      <div className="page-width detail-wrap">
        <ErrorState
          message={messageFor(event.error)}
          onRetry={() => void event.refetch()}
        />
      </div>
    );
  const item = event.data;
  const waiting = waitlist.data?.status === "WAITING";
  const promoted = waitlist.data?.status === "PROMOTED";
  const registrationNotOpen =
    renderedAt < new Date(item.registrationOpenAt).getTime();
  const registrationClosed =
    renderedAt >= new Date(item.registrationCloseAt).getTime();

  return (
    <div className="page-width detail-wrap">
      <Link className="back-link" to="/events">
        ← All events
      </Link>
      <section className="event-detail">
        <div className="event-detail__main">
          <p className="eyebrow">
            {formatEventDate(item.startsAt, item.timeZone)}
          </p>
          <h1>{item.name}</h1>
          <p className="event-detail__description">{item.description}</p>
          <div className="event-facts">
            <div>
              <span>Starts</span>
              <strong>{formatEventTime(item.startsAt, item.timeZone)}</strong>
            </div>
            <div>
              <span>Ends</span>
              <strong>{formatEventTime(item.endsAt, item.timeZone)}</strong>
            </div>
            <div>
              <span>Timezone</span>
              <strong>{item.timeZone}</strong>
            </div>
            <div>
              <span>Capacity</span>
              <strong>
                {item.capacity} {item.capacity === 1 ? "guest" : "guests"}
              </strong>
            </div>
          </div>
        </div>
        <aside
          className="reservation-panel"
          aria-labelledby="reservation-heading"
        >
          <div className="reservation-panel__availability">
            <span
              className={`availability-orb ${item.availableSeats === 0 ? "availability-orb--full" : ""}`}
            />
            <div>
              <strong>
                {item.availableSeats === 0
                  ? "At capacity"
                  : `${item.availableSeats} ${item.availableSeats === 1 ? "seat" : "seats"} available`}
              </strong>
              <span>
                {item.availableSeats === 0
                  ? "The fair waitlist is ready"
                  : "Availability is a live snapshot"}
              </span>
            </div>
          </div>
          <h2 id="reservation-heading">Your access</h2>
          {actionMessage ? (
            <InlineNotice tone={reserve.isError ? "warning" : "success"}>
              {actionMessage}
            </InlineNotice>
          ) : null}
          {!auth.token ? (
            <>
              <p>Log in to reserve a seat or join the waitlist.</p>
              <Link
                className="button button--primary button--full"
                to="/login"
                state={{ from: `/events/${id}` }}
              >
                Log in to continue
              </Link>
            </>
          ) : activeReservation.data ? (
            <div className="action-state">
              <StatusBadge status={activeReservation.data.status} />
              <p>
                Your seat is confirmed. Cancellation is final, but safe to
                repeat.
              </p>
              <Button
                variant="danger"
                pending={cancel.isPending}
                onClick={() => cancel.mutate()}
              >
                Cancel reservation
              </Button>
              {cancel.isError ? (
                <p className="action-error" role="alert">
                  {messageFor(cancel.error)}
                </p>
              ) : null}
            </div>
          ) : waiting ? (
            <div className="action-state">
              <StatusBadge status="WAITING" />
              <p>
                You are currently <strong>#{waitlist.data?.position}</strong> in
                the queue. Position is a live snapshot.
              </p>
              <Button
                variant="secondary"
                pending={leave.isPending}
                onClick={() => leave.mutate()}
              >
                Leave waitlist
              </Button>
            </div>
          ) : promoted ? (
            <div className="action-state">
              <StatusBadge status="PROMOTED" />
              <p>
                A freed seat was assigned to you. Your reservation and
                notification are durable.
              </p>
              <Link className="arrow-link" to="/account">
                View reservation →
              </Link>
            </div>
          ) : registrationNotOpen ? (
            <InlineNotice>Registration has not opened yet.</InlineNotice>
          ) : registrationClosed ? (
            <InlineNotice tone="warning">
              Registration is closed for this event.
            </InlineNotice>
          ) : item.availableSeats > 0 ? (
            <div className="action-state">
              <p>
                One click creates one logical reservation—even if the network
                makes you retry.
              </p>
              <Button
                pending={reserve.isPending}
                onClick={() => reserve.mutate()}
              >
                Reserve seat
              </Button>
            </div>
          ) : (
            <div className="action-state">
              <p>
                Join the FIFO waitlist. If a seat opens, promotion happens in
                the cancellation transaction.
              </p>
              <Button pending={join.isPending} onClick={() => join.mutate()}>
                Join waitlist
              </Button>
              {join.isError ? (
                <p className="action-error" role="alert">
                  {messageFor(join.error)}
                </p>
              ) : null}
            </div>
          )}
          <p className="reservation-panel__footnote">
            Seat state is authoritative in PostgreSQL—not this screen.
          </p>
        </aside>
      </section>
    </div>
  );
}
