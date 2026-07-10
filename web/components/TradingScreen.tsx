"use client";

import { useCallback, useEffect, useState } from "react";
import type {
  CashBalance,
  HealthReport,
  InstrumentInfo,
  OrderResponse,
  PositionInfo,
  SubmitOrderRequest,
} from "@/lib/types";
import { isTerminal, isWorking, rupees } from "@/lib/types";
import { OrderTicket } from "@/components/OrderTicket";
import { Blotter } from "@/components/Blotter";
import { HealthPanel } from "@/components/HealthPanel";
import { Positions } from "@/components/Positions";
import { LifecycleRail, StatTile } from "@/components/ui";

export function TradingScreen({
  username,
  roles,
  signOutAction,
}: {
  username: string;
  roles: string[];
  signOutAction: () => Promise<void>;
}) {
  const isOps = roles.includes("OPS");
  const isAdmin = roles.includes("ADMIN");
  const canTrade = roles.includes("TRADER");
  const canViewAll = isOps || isAdmin;
  const [scope, setScope] = useState<"own" | "all">("own");
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [instruments, setInstruments] = useState<InstrumentInfo[]>([]);
  const [positions, setPositions] = useState<PositionInfo[]>([]);
  const [health, setHealth] = useState<HealthReport | null>(null);
  const [balance, setBalance] = useState<CashBalance | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const refreshBalance = useCallback(async () => {
    const res = await fetch("/api/balances");
    if (res.ok) {
      const fresh = (await res.json()) as CashBalance;
      if (typeof fresh?.available === "number") setBalance(fresh);
    }
    const pos = await fetch("/api/positions");
    if (pos.ok) {
      const fresh = (await pos.json()) as PositionInfo[];
      if (Array.isArray(fresh)) {
        setPositions((prev) => (JSON.stringify(prev) === JSON.stringify(fresh) ? prev : fresh));
      }
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
      if (scope === "all") {
        // Cancelling on behalf of another account is deliberate, never a misclick (ADR-0022).
        const target = orders.find((o) => o.id === id);
        if (!window.confirm(`Cancel order #${id} for ${target?.account ?? "this account"}?`)) return;
      }
      await fetch(`/api/orders/${id}/cancel`, { method: "POST" });
      await refreshBalance();
    },
    [refreshBalance, scope, orders],
  );

  // Tradable instruments are reference data — one fetch per screen.
  useEffect(() => {
    (async () => {
      const res = await fetch("/api/instruments");
      if (res.ok) {
        const list = (await res.json()) as InstrumentInfo[];
        if (Array.isArray(list) && list.length) setInstruments(list);
      }
    })();
  }, []);

  // Read-model health, admins only; refreshed on the same cadence as the reconciler (60s).
  useEffect(() => {
    if (!isAdmin) return;
    const load = async () => {
      const res = await fetch("/api/reconciliation");
      if (res.ok) {
        const fresh = (await res.json()) as HealthReport;
        if (Array.isArray(fresh?.accounts)) setHealth(fresh);
      }
    };
    void load();
    const t = setInterval(load, 60_000);
    return () => clearInterval(t);
  }, [isAdmin]);

  // One list request refreshes the whole blotter — per-order polling tripped the backend
  // rate limit once an account had a handful of working orders.
  const refreshOrders = useCallback(async () => {
    const res = await fetch(`/api/orders${scope === "all" ? "?scope=all" : ""}`);
    if (res.ok) {
      const fresh = (await res.json()) as OrderResponse[];
      if (Array.isArray(fresh)) {
        // Keep the previous state object when nothing changed so effects don't re-fire.
        setOrders((prev) => (JSON.stringify(prev) === JSON.stringify(fresh) ? prev : fresh));
      }
    }
    await refreshBalance();
  }, [scope, refreshBalance]);

  // Hydrate the blotter from the backend (own history, or every account's book for ops).
  useEffect(() => {
    void refreshOrders();
  }, [refreshOrders]);

  // Poll while orders are working — the backend settles asynchronously. When the last one
  // turns terminal, take a single late look: a partial market fill can book onto an
  // already-cancelled order moments after the status lands.
  useEffect(() => {
    if (orders.length === 0) return;
    if (orders.every((o) => isTerminal(o.status))) {
      const late = setTimeout(() => void refreshOrders(), 2500);
      return () => clearTimeout(late);
    }
    const t = setInterval(() => void refreshOrders(), 1500);
    return () => clearInterval(t);
  }, [orders, refreshOrders]);

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
          {canViewAll ? (
            <>
              <button
                onClick={() => setScope("own")}
                className={scope === "own" ? "font-semibold text-ink" : "hover:text-ink2"}
              >
                My orders
              </button>
              <button
                onClick={() => setScope("all")}
                className={scope === "all" ? "font-semibold text-ink" : "hover:text-ink2"}
              >
                All accounts
              </button>
            </>
          ) : (
            <span className="font-semibold text-ink">Orders</span>
          )}
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
          {canTrade && <OrderTicket instruments={instruments} onSubmit={submit} busy={busy} />}
          <div className={`grid grid-cols-2 gap-2.5 ${canTrade ? "mt-6" : ""}`}>
            <StatTile label="Available" value={balance ? rupees(balance.available) : "—"} />
            <StatTile label="Reserved" value={balance ? rupees(balance.held) : "—"} />
            <StatTile label="Working" value={String(workingCount)} />
            <StatTile label="Filled" value={String(filledCount)} />
          </div>
        </div>

        <div className="p-5 pr-6">
          <p className="eyebrow mb-3">{scope === "all" ? "Blotter — all accounts" : "Blotter"}</p>
          <Blotter
            orders={orders}
            selectedId={selectedId}
            onSelect={setSelectedId}
            onCancel={cancel}
            showAccount={scope === "all"}
            canCancel={scope === "all" ? isOps : canTrade}
          />

          {isAdmin && health && (
            <div className="mt-6">
              <p className="eyebrow mb-3">System health</p>
              <HealthPanel report={health} />
            </div>
          )}

          {positions.length > 0 && scope === "own" && (
            <div className="mt-6">
              <p className="eyebrow mb-3">Positions</p>
              <Positions positions={positions} />
            </div>
          )}

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
