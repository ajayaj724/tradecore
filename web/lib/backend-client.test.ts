import { describe, expect, it, vi } from "vitest";
import { createBackend } from "./backend-client";

const BASE = "http://backend.test";

function fetchReturning(status: number, body: unknown) {
  return vi.fn(async () => Response.json(body, { status }));
}

describe("createBackend", () => {
  it("returns401WithoutCallingBackendWhenNoUserToken", async () => {
    const fetchFn = vi.fn();
    const backend = createBackend(BASE, async () => null, fetchFn as unknown as typeof fetch);
    const result = await backend.get(7);
    expect(result).toEqual({ ok: false, status: 401, data: null });
    expect(fetchFn).not.toHaveBeenCalled();
  });

  it("forwardsTheCurrentUsersBearerToken", async () => {
    const fetchFn = fetchReturning(200, { id: 7 });
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    await backend.get(7);
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(`${BASE}/api/v1/orders/7`);
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer user-token");
  });

  it("sendsIdempotencyKeyOnPostButNotOnGet", async () => {
    const fetchFn = fetchReturning(201, { id: 8 });
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    await backend.submit({ symbol: "INFY", side: "BUY", price: 150000, quantity: 10 });
    await backend.get(8);
    const postHeaders = (fetchFn.mock.calls[0] as unknown as [string, RequestInit])[1]
      .headers as Record<string, string>;
    const getHeaders = (fetchFn.mock.calls[1] as unknown as [string, RequestInit])[1]
      .headers as Record<string, string>;
    expect(postHeaders["Idempotency-Key"]).toMatch(/[0-9a-f-]{36}/);
    expect(postHeaders["Content-Type"]).toBe("application/json");
    expect(getHeaders["Idempotency-Key"]).toBeUndefined();
  });

  it("listsTheCurrentUsersOrders", async () => {
    const fetchFn = fetchReturning(200, [{ id: 2 }, { id: 1 }]);
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    const result = await backend.list();
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(`${BASE}/api/v1/orders`);
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer user-token");
    expect(result.data).toEqual([{ id: 2 }, { id: 1 }]);
  });

  it("requestsAllAccountsWhenOpsScopeGiven", async () => {
    const fetchFn = fetchReturning(200, []);
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    await backend.list("all");
    const [url] = fetchFn.mock.calls[0] as unknown as [string];
    expect(url).toBe(`${BASE}/api/v1/orders?scope=all`);
  });

  it("fetchesTheCurrentUsersCashBalance", async () => {
    const fetchFn = fetchReturning(200, { account: "t1", settled: 500000, held: 100000, available: 400000 });
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    const result = await backend.balance();
    const [url] = fetchFn.mock.calls[0] as unknown as [string];
    expect(url).toBe(`${BASE}/api/v1/balances`);
    expect(result.data).toEqual({ account: "t1", settled: 500000, held: 100000, available: 400000 });
  });

  it("listsTradableInstruments", async () => {
    const fetchFn = fetchReturning(200, [{ symbol: "ACME", name: "Acme Corp" }]);
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    const result = await backend.instruments();
    const [url] = fetchFn.mock.calls[0] as unknown as [string];
    expect(url).toBe(`${BASE}/api/v1/instruments`);
    expect(result.data).toEqual([{ symbol: "ACME", name: "Acme Corp" }]);
  });

  it("listsTheCurrentUsersPositions", async () => {
    const position = { symbol: "ACME", quantity: 10, totalCost: 100000, markPrice: 11000, realizedPnl: 0, unrealizedPnl: 10000 };
    const fetchFn = fetchReturning(200, [position]);
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    const result = await backend.positions();
    const [url] = fetchFn.mock.calls[0] as unknown as [string];
    expect(url).toBe(`${BASE}/api/v1/positions`);
    expect(result.data).toEqual([position]);
  });

  it("fetchesTheReconciliationReport", async () => {
    const report = { driftPairs: 0, accounts: [{ account: "trader1", equity: 1, cashDrift: 0, driftedPairs: 0 }] };
    const fetchFn = fetchReturning(200, report);
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    const result = await backend.reconciliation();
    const [url] = fetchFn.mock.calls[0] as unknown as [string];
    expect(url).toBe(`${BASE}/api/v1/reconciliation`);
    expect(result.data).toEqual(report);
  });

  it("listsAndDecidesCancelRequests", async () => {
    const fetchFn = fetchReturning(200, []);
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    await backend.cancelRequests();
    await backend.approveCancelRequest(7);
    await backend.declineCancelRequest(8);
    const urls = fetchFn.mock.calls.map((c) => (c as unknown as [string])[0]);
    expect(urls).toEqual([
      `${BASE}/api/v1/cancel-requests`,
      `${BASE}/api/v1/cancel-requests/7/approve`,
      `${BASE}/api/v1/cancel-requests/8/decline`,
    ]);
    const approveInit = (fetchFn.mock.calls[1] as unknown as [string, RequestInit])[1];
    expect(approveInit.method).toBe("POST");
  });

  it("passesBackendStatusAndBodyThrough", async () => {
    const fetchFn = fetchReturning(422, { title: "Insufficient cash" });
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    const result = await backend.submit({ symbol: "INFY", side: "BUY", price: 1, quantity: 1 });
    expect(result.ok).toBe(false);
    expect(result.status).toBe(422);
    expect(result.data).toEqual({ title: "Insufficient cash" });
  });
});
