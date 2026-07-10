"use client";

import { useCallback, useEffect, useState } from "react";
import type { CashBalance, OrderResponse, SubmitOrderRequest } from "@/lib/types";
import { isTerminal, isWorking, rupees } from "@/lib/types";
import { OrderTicket } from "@/components/OrderTicket";
import { Blotter } from "@/components/Blotter";
import { LifecycleRail, StatTile } from "@/components/ui";

export function TradingScreen({
  username,
  signOutAction,
}: {
  username: string;
  signOutAction: () => Promise<void>;
}) {
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [balance, setBalance] = useState<CashBalance | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const refreshBalance = useCallback(async () => {
    const res = await fetch("/api/balances");
    if (res.ok) {
      const fresh = (await res.json()) as CashBalance;
      if (typeof fresh?.available === "number") setBalance(fresh);
    }
  }, []);

  const upsert = (o: OrderResponse) =>
    setOrders((prev) => {
      const i = prev.findIndex((p) => p.id === o.id);
      if (i === -1) return [o, ...prev];
      const next = [...prev];
      next[i] = o;
      return next;
    });

  const submit = useCallback(async (req: SubmitOrderRequest) => {
    setBusy(true);
    try {
      const res = await fetch("/api/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(req),
      });
      const order = (await res.json()) as OrderResponse;
      if (order?.id) {
        upsert(order);
        setSelectedId(order.id);
      }
      await refreshBalance();
    } finally {
      setBusy(false);
    }
  }, [refreshBalance]);

  const cancel = useCallback(
    async (id: number) => {
      await fetch(`/api/orders/${id}/cancel`, { method: "POST" });
      await refreshBalance();
    },
    [refreshBalance],
  );

  // Hydrate the blotter with this account's order history (newest first from the backend).
  useEffect(() => {
    (async () => {
      const res = await fetch("/api/orders");
      if (res.ok) {
        const history = (await res.json()) as OrderResponse[];
        if (Array.isArray(history)) setOrders(history);
      }
      await refreshBalance();
    })();
  }, [refreshBalance]);

  // Poll working orders — the backend settles asynchronously, so status/fills arrive over time.
  useEffect(() => {
    const working = orders.filter((o) => !isTerminal(o.status));
    if (working.length === 0) return;
    const t = setInterval(async () => {
      await Promise.all(
        working.map(async (o) => {
          const res = await fetch(`/api/orders/${o.id}`);
          if (res.ok) {
            const fresh = (await res.json()) as OrderResponse;
            if (fresh?.id) upsert(fresh);
          }
        }),
      );
      await refreshBalance(); // fills/cancels settle async, so cash moves between ticks
    }, 1500);
    return () => clearInterval(t);
  }, [orders, refreshBalance]);

  const selected = orders.find((o) => o.id === selectedId) ?? null;
  const workingCount = orders.filter((o) => isWorking(o.status)).length;
  const filledCount = orders.filter((o) => o.status === "FILLED").length;

  const initials =
    (username[0] ?? "?").toUpperCase() + (/\d$/.test(username) ? username[username.length - 1] : "");

  return (
    <div className="min-w-[1040px]">
      <header className="flex items-center gap-6 border-b border-line2 bg-panel px-6 py-3.5">
        <span className="text-base font-extrabold tracking-tight">
          tradecore<span className="text-gold">.</span>
        </span>
        <nav className="flex gap-4 text-[12.5px] text-ink3">
          <span className="font-semibold text-ink">Orders</span>
        </nav>
        <div className="ml-auto flex items-center gap-3 text-[12.5px] font-semibold">
          <span className="grid h-6 w-6 place-items-center rounded-full bg-goldbg text-[11px] font-extrabold text-gold">
            {initials}
          </span>
          {username}
          <form action={signOutAction}>
            <button
              type="submit"
              className="rounded-[3px] border border-line2 px-2.5 py-1 text-[11px] font-bold text-ink2 hover:border-ink3 hover:text-ink"
            >
              Sign out
            </button>
          </form>
        </div>
      </header>

      <div className="grid grid-cols-[320px_1fr] items-start">
        <div className="min-h-[520px] border-r border-line2 p-5">
          <OrderTicket onSubmit={submit} busy={busy} />
          <div className="mt-6 grid grid-cols-2 gap-2.5">
            <StatTile label="Available" value={balance ? rupees(balance.available) : "—"} />
            <StatTile label="Reserved" value={balance ? rupees(balance.held) : "—"} />
            <StatTile label="Working" value={String(workingCount)} />
            <StatTile label="Filled" value={String(filledCount)} />
          </div>
        </div>

        <div className="p-5 pr-6">
          <p className="eyebrow mb-3">Blotter</p>
          <Blotter orders={orders} selectedId={selectedId} onSelect={setSelectedId} onCancel={cancel} />

          {selected && (
            <div className="mt-6 rounded-[4px] border border-line bg-panel p-4">
              <div className="mb-4 flex items-baseline gap-2.5">
                <span className="num text-xs text-ink3">#{selected.id}</span>
                <span className="font-bold">{selected.symbol}</span>
                <span className={`rounded-[2px] px-1.5 py-0.5 text-[10px] font-bold ${selected.side === "BUY" ? "bg-buybg text-buy" : "bg-sellbg text-sell"}`}>
                  {selected.side}
                </span>
                <span className="num ml-auto text-[11.5px] text-ink2">
                  {selected.filledQty} / {selected.quantity} filled
                </span>
              </div>
              <LifecycleRail status={selected.status} fill={selected.quantity ? selected.filledQty / selected.quantity : 0} />
              {selected.rejectReason && <p className="mt-3 text-[11.5px] text-sell">Rejected — {selected.rejectReason}</p>}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
