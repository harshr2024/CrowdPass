export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface EventSummary {
  id: string;
  name: string;
  description: string;
  capacity: number;
  availableSeats: number;
  timeZone: string;
  registrationOpenAt: string;
  registrationCloseAt: string;
  startsAt: string;
  endsAt: string;
}

export type Role = "USER" | "ORGANIZER" | "ADMIN";

export interface User {
  id: string;
  email: string;
  displayName: string;
  role: Role;
  createdAt: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: "Bearer";
  expiresIn: number;
}

export type ReservationStatus = "CONFIRMED" | "CANCELLED";

export interface Reservation {
  id: string;
  eventId: string;
  status: ReservationStatus;
  createdAt: string;
  cancelledAt: string | null;
}

export type WaitlistStatus = "WAITING" | "PROMOTED" | "LEFT";

export interface WaitlistEntry {
  id: string;
  eventId: string;
  status: WaitlistStatus;
  position: number | null;
  waitlistClosed: boolean;
  joinedAt: string;
  promotedAt: string | null;
  leftAt: string | null;
  reservationId: string | null;
}

export interface Notification {
  id: string;
  type: "WAITLIST_PROMOTED";
  eventId: string;
  eventName: string;
  reservationId: string;
  occurredAt: string;
  readAt: string | null;
}

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  requestId: string;
}
