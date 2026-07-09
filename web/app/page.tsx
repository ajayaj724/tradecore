"use client";

import { useCallback, useEffect, useState } from "react";
import type { OrderResponse, SubmitOrderRequest } from "@/lib/types";
import { isTerminal, isWorking, rupees } from "@/lib/types";
import { OrderTicket } from "@/components/OrderTicket";
import { Blotter } from "@/components/Blotter";
import { LifecycleRail, StatTile } from "@/components/ui";

export default function TradingScreen() {
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

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
    } finally {
      setBusy(false);
    }
  }, []);

  const cancel = useCallback(async (id: number) => {
    await fetch(`/api/orders/${id}/cancel`, { method: "POST" });
  }, []);

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
    }, 1500);
    return () => clearInterval(t);
  }, [orders]);

  const selected = orders.find((o) => o.id === selectedId) ?? null;
  const workingCount = orders.filter((o) => isWorking(o.status)).length;
  const reserved = orders
    .filter((o) => isWorking(o.status))
    .reduce((sum, o) => sum + o.price * (o.quantity - o.filledQty), 0);
  const filledCount = orders.filter((o) => o.status === "FILLED").length;

  return (
    <div className="min-w-[1040px]">
      <header className="flex items-center gap-6 border-b border-line2 bg-panel px-6 py-3.5">
        <span className="text-base font-extrabold tracking-tight">
          tradecore<span className="text-gold">.</span>
        </span>
        <nav className="flex gap-4 text-[12.5px] text-ink3">
          <span className="font-semibold text-ink">Orders</span>
        </nav>
        <div className="ml-auto flex items-center gap-2 text-[12.5px] font-semibold">
          <span className="grid h-6 w-6 place-items-center rounded-full bg-goldbg text-[11px] font-extrabold text-gold">T1</span>
          trader1
        </div>
      </header>

      <div className="grid grid-cols-[320px_1fr] items-start">
        <div className="min-h-[520px] border-r border-line2 p-5">
          <OrderTicket onSubmit={submit} busy={busy} />
          <div className="mt-6 grid grid-cols-2 gap-2.5">
            <StatTile label="Working" value={String(workingCount)} />
            <StatTile label="Reserved" value={rupees(reserved)} />
            <StatTile label="Filled" value={String(filledCount)} />
            <StatTile label="Orders" value={String(orders.length)} />
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
