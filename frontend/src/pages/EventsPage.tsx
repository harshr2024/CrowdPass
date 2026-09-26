import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api, messageFor } from "../api/client";
import { queryKeys } from "../api/queryKeys";
import { EventCard } from "../components/EventCard";
import { CardSkeleton, EmptyState, ErrorState } from "../components/Feedback";
import { Button } from "../components/Button";

export function EventsPage() {
  const [page, setPage] = useState(0);
  const events = useQuery({
    queryKey: queryKeys.events(page),
    queryFn: () => api.events(page, 12),
    staleTime: 30_000,
  });

  return (
    <>
      <section className="hero page-width">
        <div className="hero__glow" aria-hidden="true" />
        <p className="eyebrow">
          The seat is yours when the database says it is
        </p>
        <h1>
          Find the room
          <br />
          you want to be in.
        </h1>
        <p className="hero__copy">
          Discover upcoming events with fair reservations, a transparent
          waitlist, and updates that keep pace.
        </p>
        <div className="hero__proof" aria-label="CrowdPass principles">
          <span>
            <i /> Concurrency-safe seats
          </span>
          <span>
            <i /> Fair FIFO waitlists
          </span>
          <span>
            <i /> Durable notifications
          </span>
        </div>
      </section>
      <section
        className="page-width events-section"
        aria-labelledby="upcoming-heading"
      >
        <div className="section-heading">
          <div>
            <p className="eyebrow">Curated calendar</p>
            <h2 id="upcoming-heading">Upcoming events</h2>
          </div>
          {events.data ? (
            <span>{events.data.totalElements} published</span>
          ) : null}
        </div>
        {events.isLoading ? <CardSkeleton count={6} /> : null}
        {events.isError ? (
          <ErrorState
            message={messageFor(events.error)}
            onRetry={() => void events.refetch()}
          />
        ) : null}
        {events.data?.items.length === 0 ? (
          <EmptyState title="The calendar is quiet">
            New published events will appear here as soon as they are announced.
          </EmptyState>
        ) : null}
        {events.data?.items.length ? (
          <div className="event-grid">
            {events.data.items.map((event) => (
              <EventCard event={event} key={event.id} />
            ))}
          </div>
        ) : null}
        {events.data && events.data.totalPages > 1 ? (
          <nav className="pagination" aria-label="Event pages">
            <Button
              variant="secondary"
              disabled={page === 0}
              onClick={() => setPage((value) => value - 1)}
            >
              Previous
            </Button>
            <span>
              Page {page + 1} of {events.data.totalPages}
            </span>
            <Button
              variant="secondary"
              disabled={page + 1 >= events.data.totalPages}
              onClick={() => setPage((value) => value + 1)}
            >
              Next
            </Button>
          </nav>
        ) : null}
      </section>
    </>
  );
}
