import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createContext,
  useContext,
  useMemo,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import { api } from "../api/client";
import { queryKeys } from "../api/queryKeys";
import type { User } from "../api/types";
import { getAccessToken, setAccessToken, subscribeToSession } from "./session";

interface AuthValue {
  token: string | null;
  user: User | null;
  isLoading: boolean;
  establishSession: (token: string) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const token = useSyncExternalStore(
    subscribeToSession,
    getAccessToken,
    () => null,
  );
  const currentUser = useQuery({
    queryKey: queryKeys.me,
    queryFn: api.me,
    enabled: Boolean(token),
    retry: false,
    staleTime: 60_000,
  });

  const value = useMemo<AuthValue>(
    () => ({
      token,
      user: currentUser.data ?? null,
      isLoading: Boolean(token) && currentUser.isLoading,
      establishSession: (nextToken) => {
        setAccessToken(nextToken);
        void queryClient.invalidateQueries({ queryKey: queryKeys.me });
      },
      logout: () => {
        setAccessToken(null);
        queryClient.clear();
      },
    }),
    [currentUser.data, currentUser.isLoading, queryClient, token],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
