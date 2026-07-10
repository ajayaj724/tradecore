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

  it("passesBackendStatusAndBodyThrough", async () => {
    const fetchFn = fetchReturning(422, { title: "Insufficient cash" });
    const backend = createBackend(BASE, async () => "user-token", fetchFn as unknown as typeof fetch);
    const result = await backend.submit({ symbol: "INFY", side: "BUY", price: 1, quantity: 1 });
    expect(result.ok).toBe(false);
    expect(result.status).toBe(422);
    expect(result.data).toEqual({ title: "Insufficient cash" });
  });
});
