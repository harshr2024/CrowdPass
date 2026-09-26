import { describe, expect, it, vi } from "vitest";
import { CrowdPassApiError } from "./client";
import { ReservationAttempt, reservationFailureMessage } from "./idempotency";

describe("reservation logical attempts", () => {
  it("reuses a key for a retry and rotates only after completion", () => {
    vi.spyOn(crypto, "randomUUID")
      .mockReturnValueOnce("11111111-1111-4111-8111-111111111111")
      .mockReturnValueOnce("22222222-2222-4222-8222-222222222222");
    const attempt = new ReservationAttempt();

    expect(attempt.currentKey()).toBe("11111111-1111-4111-8111-111111111111");
    attempt.recordFailure(
      new CrowdPassApiError({ code: "NETWORK_ERROR" }, 0, null),
    );
    expect(attempt.currentKey()).toBe("11111111-1111-4111-8111-111111111111");
    attempt.finish();
    expect(attempt.currentKey()).toBe("22222222-2222-4222-8222-222222222222");
  });

  it("ends an attempt and provides actionable UI copy for EVENT_FULL", () => {
    vi.spyOn(crypto, "randomUUID")
      .mockReturnValueOnce("11111111-1111-4111-8111-111111111111")
      .mockReturnValueOnce("22222222-2222-4222-8222-222222222222");
    const attempt = new ReservationAttempt();
    const full = new CrowdPassApiError(
      { code: "EVENT_FULL", message: "Full." },
      409,
      null,
    );

    attempt.currentKey();
    attempt.recordFailure(full);

    expect(attempt.currentKey()).toBe("22222222-2222-4222-8222-222222222222");
    expect(reservationFailureMessage(full)).toContain("join the waitlist");
  });
});
