import { useMutation } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Link, Navigate, useLocation } from "react-router-dom";
import { api, messageFor } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { Button } from "../components/Button";
import { FormField } from "../components/FormField";

interface LocationState {
  from?: string;
  message?: string;
}

export function LoginPage() {
  const auth = useAuth();
  const location = useLocation();
  const state = location.state as LocationState | null;
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const login = useMutation({
    mutationFn: () => api.login({ email, password }),
    onSuccess: (response) => {
      auth.establishSession(response.accessToken);
    },
  });

  if (auth.token) return <Navigate to={state?.from ?? "/events"} replace />;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    login.mutate();
  };

  return (
    <div className="auth-layout page-width">
      <section className="auth-story">
        <p className="eyebrow">Member access</p>
        <h1>Log in</h1>
        <p>View your reservations, waitlist position, and notifications.</p>
        <div className="auth-story__signal">
          Session ends when this browser tab session closes.
        </div>
      </section>
      <section className="auth-card">
        <h2>Account details</h2>
        {state?.message ? (
          <div className="inline-notice inline-notice--success">
            {state.message}
          </div>
        ) : null}
        <form onSubmit={submit} className="auth-form">
          <FormField
            label="Email address"
            name="email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
          <FormField
            label="Password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          {login.isError ? (
            <p className="form-error" role="alert">
              {messageFor(login.error)}
            </p>
          ) : null}
          <Button type="submit" pending={login.isPending}>
            Log in
          </Button>
        </form>
        <p className="auth-card__switch">
          New here? <Link to="/register">Create an account</Link>
        </p>
      </section>
    </div>
  );
}
