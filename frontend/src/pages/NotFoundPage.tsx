import { Link } from "react-router-dom";
import { EmptyState } from "../components/Feedback";

export function NotFoundPage() {
  return (
    <div className="page-width not-found">
      <EmptyState
        title="This pass does not exist"
        action={
          <Link className="button button--primary" to="/events">
            Back to events
          </Link>
        }
      >
        The page may have moved, or the link may be incomplete.
      </EmptyState>
    </div>
  );
}
