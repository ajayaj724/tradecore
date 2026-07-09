import type { OrderResponse, SubmitOrderRequest } from "./types";

// Server-only BFF client. The browser talks to Next route handlers; those call the tradecore
// backend from the server with a Keycloak bearer token — no CORS, and the token never reaches
// the client. Dev auth uses Keycloak's password grant (like scripts/token.sh); a production
// build would swap this for a proper OIDC login and per-user tokens.

const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";
const KC = process.env.KEYCLOAK_URL ?? "http://localhost:8081";
const REALM = process.env.KEYCLOAK_REALM ?? "tradecore";
const CLIENT = process.env.KEYCLOAK_CLIENT ?? "tradecore-api";
const USER = process.env.DEMO_USER ?? "trader1";
const PASSWORD = process.env.DEMO_PASSWORD ?? "demo";

let cached: { token: string; expiresAt: number } | null = null;

async function token(): Promise<string> {
  if (cached && cached.expiresAt > Date.now() + 5_000) return cached.token;
  const res = await fetch(`${KC}/realms/${REALM}/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "password",
      client_id: CLIENT,
      username: USER,
      password: PASSWORD,
    }),
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Keycloak token request failed (${res.status})`);
  const json = (await res.json()) as { access_token: string; expires_in: number };
  cached = { token: json.access_token, expiresAt: Date.now() + json.expires_in * 1_000 };
  return cached.token;
}

export interface BackendResult<T> {
  ok: boolean;
  status: number;
  data: T | null;
}

async function call<T>(method: string, path: string, body?: unknown): Promise<BackendResult<T>> {
  const headers: Record<string, string> = { Authorization: `Bearer ${await token()}` };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (method === "POST") headers["Idempotency-Key"] = crypto.randomUUID();
  const res = await fetch(`${BACKEND}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: "no-store",
  });
  const text = await res.text();
  return { ok: res.ok, status: res.status, data: text ? (JSON.parse(text) as T) : null };
}

export const backend = {
  submit: (order: SubmitOrderRequest) => call<OrderResponse>("POST", "/api/v1/orders", order),
  cancel: (id: number) => call<OrderResponse>("POST", `/api/v1/orders/${id}/cancel`),
  get: (id: number) => call<OrderResponse>("GET", `/api/v1/orders/${id}`),
};
