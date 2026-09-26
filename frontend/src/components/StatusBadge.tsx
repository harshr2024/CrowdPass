export function StatusBadge({ status }: { status: string }) {
  const label = status.replaceAll("_", " ").toLowerCase();
  const tone = ["CONFIRMED", "PROMOTED"].includes(status)
    ? "success"
    : status === "WAITING"
      ? "waiting"
      : status === "CANCELLED" || status === "LEFT"
        ? "muted"
        : "neutral";
  return <span className={`status-badge status-badge--${tone}`}>{label}</span>;
}
