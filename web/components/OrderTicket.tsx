"use client";

import { useState } from "react";
import type { Side, OrderType, SubmitOrderRequest } from "@/lib/types";

export function OrderTicket({
  onSubmit,
  busy,
}: {
  onSubmit: (req: SubmitOrderRequest) => Promise<void>;
  busy: boolean;
}) {
  const [side, setSide] = useState<Side>("BUY");
  const [type, setType] = useState<OrderType>("LIMIT");
  const [price, setPrice] = useState("100.00");
  const [qty, setQty] = useState("5");

  const priceNum = Number(price) || 0;
  const qtyNum = Number(qty) || 0;
  const value = priceNum * qtyNum;
  const buy = side === "BUY";

  async function place() {
    await onSubmit({
      symbol: "ACME",
      side,
      price: Math.round(priceNum * 100), // ₹ → paise
      quantity: qtyNum,
      type,
    });
  }

  return (
    <div>
      <p className="eyebrow mb-3">New order · ACME</p>

      <div className="mb-3 flex overflow-hidden rounded-[3px] border border-line2">
        {(["BUY", "SELL"] as const).map((s) => (
          <button
            key={s}
            onClick={() => setSide(s)}
            className={`flex-1 border-l border-line py-2 text-xs font-bold tracking-wide first:border-l-0 ${
              side === s ? (s === "BUY" ? "bg-buy text-white" : "bg-sell text-white") : "bg-panel text-ink2"
            }`}
          >
            {s}
          </button>
        ))}
      </div>

      <div className="mb-3.5 flex gap-2">
        {(["LIMIT", "MARKET"] as const).map((t) => (
          <button
            key={t}
            onClick={() => setType(t)}
            className={`flex-1 rounded-[3px] border py-1.5 text-[11px] font-bold tracking-wide ${
              type === t ? "border-ink bg-ink text-paper" : "border-line2 bg-panel text-ink2"
            }`}
          >
            {t}
          </button>
        ))}
      </div>

      <label className="mb-3 block">
        <span className="mb-1.5 flex justify-between text-[9px] font-bold uppercase tracking-[0.1em] text-ink3">
          <span>{type === "MARKET" ? "Cap price" : "Limit price"}</span>
          <span>₹ / share</span>
        </span>
        <span className="relative block">
          <span className="num pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink3">₹</span>
          <input
            value={price}
            onChange={(e) => setPrice(e.target.value)}
            inputMode="decimal"
            className="num w-full rounded-[3px] border border-line2 bg-paper py-2.5 pl-6 pr-3 text-base outline-none focus:border-gold focus:outline-2 focus:outline-gold"
          />
        </span>
      </label>

      <label className="mb-4 block">
        <span className="mb-1.5 flex justify-between text-[9px] font-bold uppercase tracking-[0.1em] text-ink3">
          <span>Quantity</span>
          <span>shares</span>
        </span>
        <input
          value={qty}
          onChange={(e) => setQty(e.target.value)}
          inputMode="numeric"
          className="num w-full rounded-[3px] border border-line2 bg-paper px-3 py-2.5 text-base outline-none focus:border-gold focus:outline-2 focus:outline-gold"
        />
      </label>

      <button
        onClick={place}
        disabled={busy || qtyNum <= 0 || priceNum <= 0}
        className={`w-full rounded-[3px] py-3 text-sm font-bold text-white disabled:opacity-50 ${buy ? "bg-buy" : "bg-sell"}`}
      >
        {busy ? "Placing…" : `${buy ? "Buy" : "Sell"} ${qtyNum} ACME · ₹${value.toLocaleString("en-IN", { minimumFractionDigits: 2 })}`}
      </button>
      <p className="mt-2.5 text-center text-[11px] text-ink3">Pre-trade risk checks cash before the order rests.</p>
    </div>
  );
}
