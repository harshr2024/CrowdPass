# CrowdPass frontend

The browser product is React + strict TypeScript, built with Vite, React Router and TanStack Query.
It uses hand-written responsive CSS rather than a component framework.

## Run locally

Start the backend dependencies and application from the repository root:

```sh
docker compose up -d postgres redis elasticmq
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

In another terminal:

```sh
cd frontend
npm ci
npm run dev
```

Vite serves `http://localhost:5173` and proxies `/api` to `http://localhost:8080`. Override the
backend only for local development with `CROWDPASS_BACKEND_URL`; no broad backend CORS rule is
needed.

## Optional demo data

After Flyway has initialized the disposable Compose database, load three published events:

```sh
docker compose exec -T postgres psql -U crowdpass -d crowdpass < frontend/demo/seed.sql
```

Register a normal attendee in the UI. `Midnight Sessions` starts full, so that attendee can join
the waitlist. For a prepared local-only cancellation demo, log in as `demo-holder@example.invalid`
with password `CrowdPassDemo123!`, cancel its reservation, then return to the waiting attendee.
Promotion, the outbox, notification consumption and realtime invalidation all remain real backend
behavior; the frontend never synthesizes them.

## Authentication and realtime

The existing API issues bearer JWTs and has no cookie/refresh-token flow. The frontend therefore
keeps the access token in `sessionStorage`, never in a URL or log, and clears it on logout or an
authenticated 401. Closing the browser tab ends the persistent session.

Native `EventSource` cannot attach the required Authorization header. CrowdPass uses a small
fetch-based SSE parser with bounded exponential reconnect delay and `Last-Event-ID` as an
observability hint. `notifications.sync` and `notifications.changed` invalidate TanStack Query
caches; durable PostgreSQL-backed notification reads remain authoritative.

## Validation

```sh
npm run validate
npm audit
```

`validate` runs formatting, ESLint, strict TypeScript, Vitest, and the production build.
The checked-in `.npmrc` works around an npm 10 optional-peer resolver defect; direct versions remain
exact-pinned and CI still installs only from `package-lock.json`.

## Screenshots

No screenshots are committed yet. The application itself is the source of truth; future screenshots
should be captured from the real local demo flow rather than mocked UI state.
