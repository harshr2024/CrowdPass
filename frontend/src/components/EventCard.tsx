import { Link } from "react-router-dom";
import type { EventSummary } from "../api/types";
import { formatEventDate, formatEventTime } from "../lib/format";

export function EventCard({ event }: { event: EventSummary }) {
  const soldOut = event.availableSeats === 0;
  return (
    <article className="event-card">
      <div className="event-card__topline">
        <span className="event-card__date">
          {formatEventDate(event.startsAt, event.timeZone)}
        </span>
        <span className={`availability ${soldOut ? "availability--full" : ""}`}>
          {soldOut
            ? "Waitlist open"
            : `${event.availableSeats} ${event.availableSeats === 1 ? "seat" : "seats"} left`}
        </span>
      </div>
      <div>
        <h2>{event.name}</h2>
        <p>{event.description}</p>
      </div>
      <div className="event-card__footer">
        <div>
          <strong>{formatEventTime(event.startsAt, event.timeZone)}</strong>
          <span>{event.timeZone}</span>
        </div>
        <Link
          className="arrow-link"
          to={`/events/${event.id}`}
          aria-label={`View ${event.name}`}
        >
          Explore <span aria-hidden="true">↗</span>
        </Link>
      </div>
    </article>
  );
}
