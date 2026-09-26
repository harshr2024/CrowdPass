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
    <div className="page-width event-index">
      <header className="event-index__header">
        <div>
          <p className="eyebrow">CrowdPass / Calendar</p>
          <h1 id="upcoming-heading">Upcoming events</h1>
        </div>
        <p>
          Reserve a seat. If the room fills, join the line and keep your place.
        </p>
      </header>
      <section aria-labelledby="upcoming-heading">
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
          <>
            <EventCard event={events.data.items[0]!} featured />
            {events.data.items.length > 1 ? (
              <div className="event-list-heading">
                <h2>More dates</h2>
                <span>{events.data.totalElements - 1} upcoming</span>
              </div>
            ) : null}
            <div className="event-list">
              {events.data.items.slice(1).map((event) => (
                <EventCard event={event} key={event.id} />
              ))}
            </div>
          </>
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
    </div>
  );
}
