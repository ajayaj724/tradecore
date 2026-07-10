import { auth } from "@/auth";
import { createBackend } from "./backend-client";

// Server-only BFF client. Route handlers call the tradecore backend with the *current
// user's* access token from the Auth.js session — no shared service credential exists,
// and no token ever reaches the browser.

const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

export const backend = createBackend(BACKEND, async () => (await auth())?.accessToken ?? null);
