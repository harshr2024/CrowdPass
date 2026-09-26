import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";
import { AuthProvider } from "./AuthProvider";
import { ProtectedRoute } from "./ProtectedRoute";
import { resetSessionForTests } from "./session";

describe("protected routing", () => {
  afterEach(resetSessionForTests);

  it("returns an anonymous user to login and preserves the destination", () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={["/account"]}>
            <Routes>
              <Route path="/login" element={<p>Login required</p>} />
              <Route element={<ProtectedRoute />}>
                <Route path="/account" element={<p>Private account</p>} />
              </Route>
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    expect(screen.getByText("Login required")).toBeInTheDocument();
    expect(screen.queryByText("Private account")).not.toBeInTheDocument();
  });
});
