import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAuth } from "../auth/AuthProvider";
import { LoginPage } from "./LoginPage";

vi.mock("../auth/AuthProvider", () => ({ useAuth: vi.fn() }));

describe("login routing", () => {
  beforeEach(() => {
    vi.mocked(useAuth).mockReturnValue({
      token: "session-token",
      user: null,
      isLoading: false,
      establishSession: vi.fn(),
      logout: vi.fn(),
    });
  });

  it("returns an authenticated session to the protected destination", () => {
    const client = new QueryClient();
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter
          initialEntries={[
            {
              pathname: "/login",
              state: { from: "/events/event-42" },
            },
          ]}
        >
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route
              path="/events/:id"
              element={<p>Returned to event detail</p>}
            />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.getByText("Returned to event detail")).toBeInTheDocument();
  });
});
