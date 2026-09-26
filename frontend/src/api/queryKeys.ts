export const queryKeys = {
  eventLists: ["events"] as const,
  events: (page: number) => ["events", page] as const,
  eventDetails: ["event"] as const,
  event: (id: string) => ["event", id] as const,
  me: ["me"] as const,
  reservations: ["reservations"] as const,
  activeReservations: ["active-reservation"] as const,
  activeReservation: (eventId: string) =>
    ["active-reservation", eventId] as const,
  waitlists: ["waitlist"] as const,
  waitlist: (eventId: string) => ["waitlist", eventId] as const,
  notifications: ["notifications"] as const,
};
