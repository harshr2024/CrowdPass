import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { api, messageFor } from "../api/client";
import { queryKeys } from "../api/queryKeys";
import type { Notification } from "../api/types";
import { Button } from "../components/Button";
import { EmptyState, ErrorState, PageLoader } from "../components/Feedback";
import { formatTimestamp } from "../lib/format";

function notificationCopy(notification: Notification): string {
  if (notification.type === "WAITLIST_PROMOTED")
    return `A seat opened up. You were promoted from the waitlist for ${notification.eventName}.`;
  return `There is an update for ${notification.eventName}.`;
}

export function NotificationsPage() {
  const [page, setPage] = useState(0);
  const queryClient = useQueryClient();
  const notifications = useQuery({
    queryKey: [...queryKeys.notifications, page],
    queryFn: () => api.notifications(page, 20),
  });
  const markRead = useMutation({
    mutationFn: api.markNotificationRead,
    onSuccess: async () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.notifications }),
  });

  if (notifications.isLoading)
    return <PageLoader label="Checking notifications" />;
  if (notifications.isError)
    return (
      <div className="page-width notifications-page">
        <ErrorState
          message={messageFor(notifications.error)}
          onRetry={() => void notifications.refetch()}
        />
      </div>
    );

  return (
    <div className="page-width notifications-page">
      <header className="page-header">
        <p className="eyebrow">Durable updates</p>
        <h1>Notifications</h1>
        <p>
          Realtime signals bring you back here; PostgreSQL keeps every
          notification authoritative.
        </p>
      </header>
      {notifications.data?.items.length === 0 ? (
        <EmptyState
          title="You are all caught up"
          action={
            <Link className="button button--secondary" to="/events">
              Browse events
            </Link>
          }
        >
          Promotions and reservation updates will appear here.
        </EmptyState>
      ) : (
        <div className="notification-list">
          {notifications.data?.items.map((notification) => (
            <article
              className={`notification-item ${notification.readAt ? "" : "notification-item--unread"}`}
              key={notification.id}
            >
              <div className="notification-item__mark" aria-hidden="true">
                {notification.readAt ? "✓" : "!"}
              </div>
              <div className="notification-item__body">
                <div>
                  <strong>Seat confirmed</strong>
                  <time dateTime={notification.occurredAt}>
                    {formatTimestamp(notification.occurredAt)}
                  </time>
                </div>
                <p>{notificationCopy(notification)}</p>
                <Link
                  className="arrow-link"
                  to={`/events/${notification.eventId}`}
                >
                  View event →
                </Link>
              </div>
              {!notification.readAt ? (
                <Button
                  variant="quiet"
                  pending={
                    markRead.isPending && markRead.variables === notification.id
                  }
                  onClick={() => markRead.mutate(notification.id)}
                >
                  Mark read
                </Button>
              ) : null}
            </article>
          ))}
        </div>
      )}
      {markRead.isError ? (
        <p className="form-error" role="alert">
          {messageFor(markRead.error)}
        </p>
      ) : null}
      {notifications.data && notifications.data.totalPages > 1 ? (
        <nav className="pagination" aria-label="Notification pages">
          <Button
            variant="secondary"
            disabled={page === 0}
            onClick={() => setPage((value) => value - 1)}
          >
            Previous
          </Button>
          <span>
            Page {page + 1} of {notifications.data.totalPages}
          </span>
          <Button
            variant="secondary"
            disabled={page + 1 >= notifications.data.totalPages}
            onClick={() => setPage((value) => value + 1)}
          >
            Next
          </Button>
        </nav>
      ) : null}
    </div>
  );
}
