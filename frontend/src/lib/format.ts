export function formatEventDate(value: string, timeZone: string): string {
  return new Intl.DateTimeFormat(undefined, {
    weekday: "short",
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone,
  }).format(new Date(value));
}

export function formatEventTime(value: string, timeZone: string): string {
  return new Intl.DateTimeFormat(undefined, {
    hour: "numeric",
    minute: "2-digit",
    timeZone,
    timeZoneName: "short",
  }).format(new Date(value));
}

export function formatEventDateParts(
  value: string,
  timeZone: string,
): { month: string; day: string } {
  const parts = new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    timeZone,
  }).formatToParts(new Date(value));
  return {
    month: parts.find((part) => part.type === "month")?.value ?? "—",
    day: parts.find((part) => part.type === "day")?.value ?? "·",
  };
}

export function formatEventCalendarParts(
  value: string,
  timeZone: string,
): { weekday: string; month: string; day: string; year: string } {
  const parts = new Intl.DateTimeFormat(undefined, {
    weekday: "short",
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone,
  }).formatToParts(new Date(value));
  const read = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? "";
  return {
    weekday: read("weekday"),
    month: read("month"),
    day: read("day"),
    year: read("year"),
  };
}

export function formatTimestamp(value: string): string {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(value));
}
