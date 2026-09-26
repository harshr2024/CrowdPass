import { Link } from "react-router-dom";
import type { EventSummary } from "../api/types";
import { formatEventCalendarParts, formatEventTime } from "../lib/format";

export function EventCard({
  event,
  featured = false,
}: {
  event: EventSummary;
  featured?: boolean;
}) {
  const soldOut = event.availableSeats === 0;
  const date = formatEventCalendarParts(event.startsAt, event.timeZone);
  return (
    <article
      className={`event-entry ${featured ? "event-entry--featured" : ""}`}
    >
      <time className="event-entry__date" dateTime={event.startsAt}>
        <span>{date.weekday}</span>
        <strong>{date.day}</strong>
        <span>
          {date.month} {date.year}
        </span>
      </time>
      <div className="event-entry__body">
        <div className="event-entry__status-row">
          <span
            className={`availability ${soldOut ? "availability--full" : ""}`}
          >
            {soldOut
              ? "Waitlist open"
              : `${event.availableSeats} ${event.availableSeats === 1 ? "seat" : "seats"} left`}
          </span>
          <span>{event.timeZone}</span>
        </div>
        <h2>
          <Link to={`/events/${event.id}`}>{event.name}</Link>
        </h2>
        <p>{event.description}</p>
      </div>
      <div className="event-entry__action">
        <span>{formatEventTime(event.startsAt, event.timeZone)}</span>
        <Link
          className="arrow-link"
          to={`/events/${event.id}`}
          aria-label={`View ${event.name}`}
        >
          View event <span aria-hidden="true">→</span>
        </Link>
      </div>
    </article>
  );
}
