import { useEffect, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { queryKeys } from "../api/queryKeys";
import { setAccessToken } from "../auth/session";

export interface StreamEvent {
  event: string;
  data: string;
  id?: string;
}

export async function readSseStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (event: StreamEvent) => void,
): Promise<void> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let eventName = "message";
  let data: string[] = [];
  let id: string | undefined;

  const dispatch = () => {
    if (data.length > 0)
      onEvent({
        event: eventName,
        data: data.join("\n"),
        ...(id ? { id } : {}),
      });
    eventName = "message";
    data = [];
    id = undefined;
  };

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replaceAll("\r\n", "\n");
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (line === "") dispatch();
      else if (line.startsWith("event:")) eventName = line.slice(6).trimStart();
      else if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
      else if (line.startsWith("id:")) id = line.slice(3).trimStart();
    }
    if (done) {
      if (buffer) {
        if (buffer.startsWith("data:")) data.push(buffer.slice(5).trimStart());
      }
      dispatch();
      return;
    }
  }
}

export function useNotificationStream(token: string | null): void {
  const queryClient = useQueryClient();
  const lastEventId = useRef<string | null>(null);

  useEffect(() => {
    if (!token) return undefined;
    const controller = new AbortController();
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    let delay = 1_000;

    const connect = async () => {
      try {
        const headers = new Headers({
          Accept: "text/event-stream",
          Authorization: `Bearer ${token}`,
        });
        if (lastEventId.current)
          headers.set("Last-Event-ID", lastEventId.current);
        const response = await fetch("/api/notifications/stream", {
          headers,
          signal: controller.signal,
        });
        if (response.status === 401) {
          setAccessToken(null);
          return;
        }
        if (!response.ok || !response.body)
          throw new Error("Realtime stream unavailable");
        delay = 1_000;
        await readSseStream(response.body, (event) => {
          if (event.id) lastEventId.current = event.id;
          if (
            event.event === "notifications.sync" ||
            event.event === "notifications.changed"
          ) {
            void queryClient.invalidateQueries({
              queryKey: queryKeys.notifications,
            });
            void queryClient.invalidateQueries({
              queryKey: queryKeys.reservations,
            });
            void queryClient.invalidateQueries({
              queryKey: queryKeys.activeReservations,
            });
            void queryClient.invalidateQueries({
              queryKey: queryKeys.waitlists,
            });
            void queryClient.invalidateQueries({
              queryKey: queryKeys.eventLists,
            });
            void queryClient.invalidateQueries({
              queryKey: queryKeys.eventDetails,
            });
          }
        });
      } catch (error) {
        if (controller.signal.aborted) return;
        if (error instanceof DOMException && error.name === "AbortError")
          return;
      }
      if (!controller.signal.aborted) {
        reconnectTimer = setTimeout(() => void connect(), delay);
        delay = Math.min(delay * 2, 30_000);
      }
    };

    void connect();
    return () => {
      controller.abort();
      if (reconnectTimer) clearTimeout(reconnectTimer);
    };
  }, [queryClient, token]);
}
