import type { ReactNode } from "react";
import { Button } from "./Button";

export function PageLoader({
  label = "Loading CrowdPass",
}: {
  label?: string;
}) {
  return (
    <div className="page-loader" role="status">
      <span className="page-loader__mark" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

export function CardSkeleton({ count = 3 }: { count?: number }) {
  return (
    <div className="event-grid" aria-label="Loading events" aria-busy="true">
      {Array.from({ length: count }, (_, index) => (
        <div className="event-card event-card--skeleton" key={index}>
          <span className="skeleton skeleton--eyebrow" />
          <span className="skeleton skeleton--title" />
          <span className="skeleton skeleton--text" />
          <span className="skeleton skeleton--text-short" />
        </div>
      ))}
    </div>
  );
}

export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <section className="empty-state">
      <span className="empty-state__icon" aria-hidden="true">
        ✦
      </span>
      <h2>{title}</h2>
      <p>{children}</p>
      {action}
    </section>
  );
}

export function ErrorState({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}) {
  return (
    <section className="error-state" role="alert">
      <span className="status-dot status-dot--error" aria-hidden="true" />
      <div>
        <h2>We hit a snag</h2>
        <p>{message}</p>
      </div>
      {onRetry ? (
        <Button type="button" variant="secondary" onClick={onRetry}>
          Try again
        </Button>
      ) : null}
    </section>
  );
}

export function InlineNotice({
  tone = "info",
  children,
}: {
  tone?: "info" | "success" | "warning";
  children: ReactNode;
}) {
  return (
    <div className={`inline-notice inline-notice--${tone}`} role="status">
      {children}
    </div>
  );
}
