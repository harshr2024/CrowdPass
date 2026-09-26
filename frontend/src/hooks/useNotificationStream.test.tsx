import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { queryKeys } from "../api/queryKeys";
import { readSseStream, useNotificationStream } from "./useNotificationStream";

function streamOf(...chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });
}

describe("fetch-based notification SSE", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("parses named events split across network chunks", async () => {
    const events: Array<{ event: string; data: string; id?: string }> = [];
    await readSseStream(
      streamOf(
        "event:notific",
        'ations.changed\nid:abc\ndata:{"notificationId":"abc"}\n\n',
      ),
      (event) => events.push(event),
    );
    expect(events).toEqual([
      {
        event: "notifications.changed",
        id: "abc",
        data: '{"notificationId":"abc"}',
      },
    ]);
  });

  it("invalidates durable notifications when a realtime signal arrives", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            streamOf('event:notifications.sync\ndata:{"version":1}\n\n'),
            { status: 200, headers: { "Content-Type": "text/event-stream" } },
          ),
        ),
    );
    const client = new QueryClient();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );

    const hook = renderHook(() => useNotificationStream("token-never-logged"), {
      wrapper,
    });
    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith({
        queryKey: queryKeys.notifications,
      }),
    );
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: queryKeys.activeReservations,
    });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.waitlists });
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: queryKeys.eventDetails,
    });
    hook.unmount();
  });
});
