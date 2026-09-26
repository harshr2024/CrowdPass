import { CrowdPassApiError } from "./client";

/** Keeps one key across transport/server retries, then rotates for the next logical action. */
export class ReservationAttempt {
  private key: string | null = null;

  currentKey(): string {
    this.key ??= crypto.randomUUID();
    return this.key;
  }

  finish(): void {
    this.key = null;
  }

  recordFailure(error: unknown): void {
    if (
      error instanceof CrowdPassApiError &&
      error.status > 0 &&
      error.status < 500
    )
      this.finish();
  }
}

export function reservationFailureMessage(error: unknown): string | null {
  if (error instanceof CrowdPassApiError && error.code === "EVENT_FULL") {
    return "That last seat was just claimed. You can join the waitlist now.";
  }
  return null;
}
