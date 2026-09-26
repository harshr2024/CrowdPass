import { useQuery } from "@tanstack/react-query";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { api } from "../api/client";
import { queryKeys } from "../api/queryKeys";
import { useAuth } from "../auth/AuthProvider";
import { useNotificationStream } from "../hooks/useNotificationStream";
import { Button } from "./Button";

export function AppShell() {
  const auth = useAuth();
  const navigate = useNavigate();
  useNotificationStream(auth.token);
  const notifications = useQuery({
    queryKey: [...queryKeys.notifications, 0],
    queryFn: () => api.notifications(0, 20),
    enabled: Boolean(auth.token),
    staleTime: 20_000,
  });
  const unread =
    notifications.data?.items.filter((item) => !item.readAt).length ?? 0;

  return (
    <div className="app-shell">
      <header className="site-header">
        <div className="site-header__inner">
          <NavLink to="/events" className="brand" aria-label="CrowdPass events">
            <span className="brand__mark" aria-hidden="true">
              C
            </span>
            <span>CrowdPass</span>
          </NavLink>
          <nav className="site-nav" aria-label="Main navigation">
            <NavLink to="/events">Events</NavLink>
            {auth.token ? (
              <>
                <NavLink to="/notifications" className="nav-with-count">
                  Notifications
                  {unread > 0 ? (
                    <span className="nav-count">{Math.min(unread, 9)}</span>
                  ) : null}
                </NavLink>
                <NavLink to="/account">Account</NavLink>
              </>
            ) : null}
          </nav>
          <div className="site-header__actions">
            {auth.token ? (
              <Button
                variant="quiet"
                onClick={() => {
                  auth.logout();
                  void navigate("/events");
                }}
              >
                Log out
              </Button>
            ) : (
              <>
                <NavLink className="text-link" to="/login">
                  Log in
                </NavLink>
                <NavLink
                  className="button button--primary button--compact"
                  to="/register"
                >
                  Join CrowdPass
                </NavLink>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="main-content">
        <Outlet />
      </main>
      <footer className="site-footer">
        <span>CrowdPass</span>
        <p>Fair reservations. Durable state. Realtime updates.</p>
      </footer>
    </div>
  );
}
