import { afterEach, describe, expect, it, vi } from "vitest";
import { api, CrowdPassApiError } from "./client";
import {
  getAccessToken,
  resetSessionForTests,
  setAccessToken,
} from "../auth/session";

describe("typed API client", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    resetSessionForTests();
  });

  it("parses the standard ApiError and preserves the request id", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            status: 409,
            code: "EVENT_FULL",
            message: "The event is full.",
            requestId: "request-42",
          }),
          {
            status: 409,
            headers: {
              "Content-Type": "application/json",
              "X-Request-Id": "request-42",
            },
          },
        ),
      ),
    );

    await expect(
      api.reserve("event-1", "11111111-1111-4111-8111-111111111111"),
    ).rejects.toMatchObject({
      status: 409,
      code: "EVENT_FULL",
      message: "The event is full.",
      requestId: "request-42",
    });
  });

  it("clears an expired session on authenticated 401 without logging the token", async () => {
    setAccessToken("sensitive-token");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            status: 401,
            code: "UNAUTHENTICATED",
            message: "Authentication is required.",
          }),
          { status: 401, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(api.me()).rejects.toBeInstanceOf(CrowdPassApiError);
    expect(getAccessToken()).toBeNull();
  });

  it("attaches the same supplied idempotency key to the reservation request", async () => {
    setAccessToken("session-token");
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "reservation-1",
          eventId: "event-1",
          status: "CONFIRMED",
          createdAt: new Date().toISOString(),
          cancelledAt: null,
        }),
        { status: 201, headers: { "Content-Type": "application/json" } },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    await api.reserve("event-1", "same-logical-action-key");

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      "same-logical-action-key",
    );
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer session-token",
    );
  });
});
