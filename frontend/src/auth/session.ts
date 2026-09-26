const STORAGE_KEY = "crowdpass.session.token";

type Listener = () => void;

let token = readStoredToken();
const listeners = new Set<Listener>();

function readStoredToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.sessionStorage.getItem(STORAGE_KEY);
}

export function getAccessToken(): string | null {
  return token;
}

export function setAccessToken(next: string | null): void {
  token = next;
  if (typeof window !== "undefined") {
    if (next) window.sessionStorage.setItem(STORAGE_KEY, next);
    else window.sessionStorage.removeItem(STORAGE_KEY);
  }
  listeners.forEach((listener) => listener());
}

export function subscribeToSession(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function resetSessionForTests(): void {
  setAccessToken(null);
}
