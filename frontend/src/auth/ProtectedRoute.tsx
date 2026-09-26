import { Navigate, Outlet, useLocation } from "react-router-dom";
import { PageLoader } from "../components/Feedback";
import { useAuth } from "./AuthProvider";

export function ProtectedRoute() {
  const auth = useAuth();
  const location = useLocation();

  if (auth.isLoading)
    return <PageLoader label="Restoring your CrowdPass session" />;
  if (!auth.token)
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <Outlet />;
}
