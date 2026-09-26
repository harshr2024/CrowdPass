import { useMutation } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { api, messageFor } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { Button } from "../components/Button";
import { FormField } from "../components/FormField";

export function RegisterPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const register = useMutation({
    mutationFn: async () => {
      await api.register({ displayName, email, password });
      return api.login({ email, password });
    },
    onSuccess: (response) => {
      auth.establishSession(response.accessToken);
      void navigate("/events", { replace: true });
    },
  });

  if (auth.token) return <Navigate to="/events" replace />;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    register.mutate();
  };

  return (
    <div className="auth-layout page-width">
      <section className="auth-story auth-story--register">
        <p className="eyebrow">CrowdPass membership</p>
        <h1>Create account</h1>
        <p>
          Reserve seats, join waitlists, and keep every update in one place.
        </p>
      </section>
      <section className="auth-card">
        <h2>Your details</h2>
        <form onSubmit={submit} className="auth-form">
          <FormField
            label="Display name"
            name="displayName"
            autoComplete="name"
            required
            maxLength={100}
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
          <FormField
            label="Email address"
            name="email"
            type="email"
            autoComplete="email"
            required
            maxLength={254}
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
          <FormField
            label="Password"
            name="password"
            type="password"
            autoComplete="new-password"
            required
            minLength={12}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            hint="Use at least 12 characters."
          />
          {register.isError ? (
            <p className="form-error" role="alert">
              {messageFor(register.error)}
            </p>
          ) : null}
          <Button type="submit" pending={register.isPending}>
            Create account
          </Button>
        </form>
        <p className="auth-card__switch">
          Already registered? <Link to="/login">Log in</Link>
        </p>
      </section>
    </div>
  );
}
