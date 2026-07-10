import type {
  CancelRequestInfo,
  CashBalance,
  HealthReport,
  InstrumentInfo,
  OrderResponse,
  PositionInfo,
  SubmitOrderRequest,
} from "./types";

// Pure backend HTTP client. The access token of the *current user* is supplied per call by
// the injected getToken — no ambient service credential exists anywhere in the web tier.

export interface BackendResult<T> {
  ok: boolean;
  status: number;
  data: T | null;
}

export function createBackend(
  baseUrl: string,
  getToken: () => Promise<string | null>,
  fetchFn: typeof fetch = fetch,
) {
  async function call<T>(method: string, path: string, body?: unknown): Promise<BackendResult<T>> {
    const token = await getToken();
    if (!token) return { ok: false, status: 401, data: null };
    const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method === "POST") headers["Idempotency-Key"] = crypto.randomUUID();
    const res = await fetchFn(`${baseUrl}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      cache: "no-store",
    });
    const text = await res.text();
    return { ok: res.ok, status: res.status, data: text ? (JSON.parse(text) as T) : null };
  }

  return {
    submit: (order: SubmitOrderRequest) => call<OrderResponse>("POST", "/api/v1/orders", order),
    cancel: (id: number) => call<OrderResponse>("POST", `/api/v1/orders/${id}/cancel`),
    get: (id: number) => call<OrderResponse>("GET", `/api/v1/orders/${id}`),
    list: (scope?: "all") => call<OrderResponse[]>("GET", `/api/v1/orders${scope === "all" ? "?scope=all" : ""}`),
    balance: () => call<CashBalance>("GET", "/api/v1/balances"),
    instruments: () => call<InstrumentInfo[]>("GET", "/api/v1/instruments"),
    positions: () => call<PositionInfo[]>("GET", "/api/v1/positions"),
    reconciliation: () => call<HealthReport>("GET", "/api/v1/reconciliation"),
    cancelRequests: () => call<CancelRequestInfo[]>("GET", "/api/v1/cancel-requests"),
    approveCancelRequest: (id: number) => call<void>("POST", `/api/v1/cancel-requests/${id}/approve`),
    declineCancelRequest: (id: number) => call<void>("POST", `/api/v1/cancel-requests/${id}/decline`),
  };
}
