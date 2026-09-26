import {
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { api, messageFor } from "../api/client";
import { queryKeys } from "../api/queryKeys";
import { useAuth } from "../auth/AuthProvider";
import { Button } from "../components/Button";
import { EmptyState, ErrorState, PageLoader } from "../components/Feedback";
import { StatusBadge } from "../components/StatusBadge";
import {
  formatEventDateParts,
  formatEventTime,
  formatTimestamp,
} from "../lib/format";

export function AccountPage() {
  const auth = useAuth();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const reservations = useQuery({
    queryKey: [...queryKeys.reservations, page],
    queryFn: () => api.reservations(page, 10),
  });
  const eventIds = [
    ...new Set(reservations.data?.items.map((item) => item.eventId) ?? []),
  ];
  const events = useQueries({
    queries: eventIds.map((id) => ({
      queryKey: queryKeys.event(id),
      queryFn: () => api.event(id),
      staleTime: 60_000,
    })),
  });
  const eventById = new Map(
    events.flatMap((query) =>
      query.data ? [[query.data.id, query.data] as const] : [],
    ),
  );
  const cancel = useMutation({
    mutationFn: api.cancelReservation,
    onSuccess: async (reservation) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.reservations }),
        queryClient.invalidateQueries({
          queryKey: queryKeys.event(reservation.eventId),
        }),
      ]);
    },
  });

  if (auth.isLoading || reservations.isLoading)
    return <PageLoader label="Loading your account" />;
  if (reservations.isError)
    return (
      <div className="page-width account-page">
        <ErrorState message={messageFor(reservations.error)} />
      </div>
    );

  return (
    <div className="page-width account-page">
      <header className="account-hero">
        <div>
          <p className="eyebrow">Your CrowdPass</p>
          <h1>{auth.user?.displayName ?? "Account"}</h1>
          <p>{auth.user?.email}</p>
        </div>
        <div className="account-meta">
          <span>Member since</span>
          <strong>
            {auth.user ? formatTimestamp(auth.user.createdAt) : "—"}
          </strong>
        </div>
      </header>
      <section
        className="account-section"
        aria-labelledby="reservations-heading"
      >
        <div className="section-heading">
          <div>
            <p className="eyebrow">Your activity</p>
            <h2 id="reservations-heading">Reservations</h2>
          </div>
          <span>{reservations.data?.totalElements ?? 0} total</span>
        </div>
        {reservations.data?.items.length === 0 ? (
          <EmptyState
            title="No reservations yet"
            action={
              <Link className="button button--primary" to="/events">
                Explore events
              </Link>
            }
          >
            When you claim a seat, it will appear here.
          </EmptyState>
        ) : (
          <div className="reservation-list">
            {reservations.data?.items.map((reservation) => {
              const event = eventById.get(reservation.eventId);
              const date = event
                ? formatEventDateParts(event.startsAt, event.timeZone)
                : null;
              return (
                <article className="reservation-row" key={reservation.id}>
                  <div className="reservation-row__date" aria-hidden="true">
                    <span>{date?.month ?? "—"}</span>
                    <strong>{date?.day ?? "·"}</strong>
                  </div>
                  <div className="reservation-row__main">
                    <div>
                      <StatusBadge status={reservation.status} />
                      <span>
                        Reserved {formatTimestamp(reservation.createdAt)}
                      </span>
                    </div>
                    <h3>{event?.name ?? "Event details unavailable"}</h3>
                    {event ? (
                      <p>
                        {formatEventTime(event.startsAt, event.timeZone)} ·{" "}
                        {event.timeZone}
                      </p>
                    ) : null}
                  </div>
                  <div className="reservation-row__actions">
                    <Link
                      className="arrow-link"
                      to={`/events/${reservation.eventId}`}
                    >
                      View event
                    </Link>
                    {reservation.status === "CONFIRMED" ? (
                      <Button
                        variant="quiet"
                        pending={
                          cancel.isPending &&
                          cancel.variables === reservation.id
                        }
                        onClick={() => cancel.mutate(reservation.id)}
                      >
                        Cancel
                      </Button>
                    ) : null}
                  </div>
                </article>
              );
            })}
          </div>
        )}
        {cancel.isError ? (
          <p className="form-error" role="alert">
            {messageFor(cancel.error)}
          </p>
        ) : null}
        {reservations.data && reservations.data.totalPages > 1 ? (
          <nav className="pagination" aria-label="Reservation pages">
            <Button
              variant="secondary"
              disabled={page === 0}
              onClick={() => setPage((value) => value - 1)}
            >
              Previous
            </Button>
            <span>
              Page {page + 1} of {reservations.data.totalPages}
            </span>
            <Button
              variant="secondary"
              disabled={page + 1 >= reservations.data.totalPages}
              onClick={() => setPage((value) => value + 1)}
            >
              Next
            </Button>
          </nav>
        ) : null}
      </section>
    </div>
  );
}
