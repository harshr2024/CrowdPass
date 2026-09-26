import { getAccessToken, setAccessToken } from "../auth/session";
import type {
  ApiErrorBody,
  EventSummary,
  LoginResponse,
  Notification,
  PageResponse,
  Reservation,
  User,
  WaitlistEntry,
} from "./types";

export class CrowdPassApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | null;

  constructor(
    body: Partial<ApiErrorBody>,
    status: number,
    requestId: string | null,
  ) {
    super(body.message ?? "CrowdPass could not complete that request.");
    this.name = "CrowdPassApiError";
    this.status = status;
    this.code = body.code ?? "UNEXPECTED_RESPONSE";
    this.requestId = body.requestId ?? requestId;
  }
}

interface RequestOptions extends RequestInit {
  authenticated?: boolean;
}

async function request<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const { authenticated = false, headers: providedHeaders, ...init } = options;
  const headers = new Headers(providedHeaders);
  headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");

  const token = getAccessToken();
  if (authenticated && token) headers.set("Authorization", `Bearer ${token}`);

  let response: Response;
  try {
    response = await fetch(path, { ...init, headers });
  } catch {
    throw new CrowdPassApiError(
      {
        code: "NETWORK_ERROR",
        message:
          "CrowdPass is unreachable. Check your connection and try again.",
      },
      0,
      null,
    );
  }

  const requestId = response.headers.get("X-Request-Id");
  if (response.status === 401 && authenticated) setAccessToken(null);

  if (!response.ok) {
    const body = await parseJson<Partial<ApiErrorBody>>(response);
    throw new CrowdPassApiError(body ?? {}, response.status, requestId);
  }

  const body = await parseJson<T>(response);
  if (body === null) {
    throw new CrowdPassApiError(
      {
        code: "UNEXPECTED_RESPONSE",
        message: "CrowdPass returned an unreadable response.",
      },
      response.status,
      requestId,
    );
  }
  return body;
}

async function parseJson<T>(response: Response): Promise<T | null> {
  try {
    return (await response.json()) as T;
  } catch {
    return null;
  }
}

const json = (value: unknown) => JSON.stringify(value);

export const api = {
  events: (page = 0, size = 12) =>
    request<PageResponse<EventSummary>>(
      `/api/events?page=${page}&size=${size}`,
    ),
  event: (id: string) => request<EventSummary>(`/api/events/${id}`),
  register: (input: { email: string; password: string; displayName: string }) =>
    request<User>("/api/auth/register", { method: "POST", body: json(input) }),
  login: (input: { email: string; password: string }) =>
    request<LoginResponse>("/api/auth/login", {
      method: "POST",
      body: json(input),
    }),
  me: () => request<User>("/api/users/me", { authenticated: true }),
  reservations: (page = 0, size = 20) =>
    request<PageResponse<Reservation>>(
      `/api/reservations?page=${page}&size=${size}`,
      { authenticated: true },
    ),
  activeReservation: async (eventId: string): Promise<Reservation | null> => {
    try {
      return await request<Reservation>(
        `/api/events/${eventId}/reservation/me`,
        { authenticated: true },
      );
    } catch (error) {
      if (
        error instanceof CrowdPassApiError &&
        error.code === "RESERVATION_NOT_FOUND"
      )
        return null;
      throw error;
    }
  },
  reserve: (eventId: string, idempotencyKey: string) =>
    request<Reservation>(`/api/events/${eventId}/reservations`, {
      method: "POST",
      authenticated: true,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
  cancelReservation: (id: string) =>
    request<Reservation>(`/api/reservations/${id}/cancel`, {
      method: "POST",
      authenticated: true,
    }),
  waitlist: async (eventId: string): Promise<WaitlistEntry | null> => {
    try {
      return await request<WaitlistEntry>(
        `/api/events/${eventId}/waitlist/me`,
        { authenticated: true },
      );
    } catch (error) {
      if (
        error instanceof CrowdPassApiError &&
        error.code === "WAITLIST_ENTRY_NOT_FOUND"
      )
        return null;
      throw error;
    }
  },
  joinWaitlist: (eventId: string) =>
    request<WaitlistEntry>(`/api/events/${eventId}/waitlist`, {
      method: "POST",
      authenticated: true,
    }),
  leaveWaitlist: (eventId: string) =>
    request<WaitlistEntry>(`/api/events/${eventId}/waitlist/me/leave`, {
      method: "POST",
      authenticated: true,
    }),
  notifications: (page = 0, size = 20) =>
    request<PageResponse<Notification>>(
      `/api/notifications?page=${page}&size=${size}`,
      { authenticated: true },
    ),
  markNotificationRead: (id: string) =>
    request<Notification>(`/api/notifications/${id}/read`, {
      method: "POST",
      authenticated: true,
    }),
};

export function messageFor(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Something unexpected happened.";
}
