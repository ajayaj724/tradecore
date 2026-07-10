"use client";

import type { PositionInfo } from "@/lib/types";

/** Paise → ₹ text for table cells; sign kept, no currency mark (column headers carry it). */
const money = (paise: number) =>
  (paise / 100).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function Pnl({ value }: { value: number }) {
  const tone = value > 0 ? "text-buy" : value < 0 ? "text-sell" : "text-ink3";
  return (
    <span className={`num ${tone}`}>
      {value > 0 ? "+" : ""}
      {money(value)}
    </span>
  );
}

export function Positions({ positions }: { positions: PositionInfo[] }) {
  return (
    <div className="overflow-hidden rounded-[4px] border border-line bg-panel">
      <table className="w-full border-collapse text-xs">
        <thead>
          <tr className="bg-[#f1eee6] text-[9px] uppercase tracking-[0.1em] text-ink3">
            <th className="px-3.5 py-2.5 text-left font-bold">Symbol</th>
            <th className="px-3.5 py-2.5 text-right font-bold">Qty</th>
            <th className="px-3.5 py-2.5 text-right font-bold">Avg cost ₹</th>
            <th className="px-3.5 py-2.5 text-right font-bold">Mark ₹</th>
            <th className="px-3.5 py-2.5 text-right font-bold">Realized ₹</th>
            <th className="px-3.5 py-2.5 text-right font-bold">Unrealized ₹</th>
          </tr>
        </thead>
        <tbody>
          {positions.map((p) => (
            <tr key={p.symbol} className="border-t border-line">
              <td className="px-3.5 py-2.5 font-bold">{p.symbol}</td>
              <td className="num px-3.5 py-2.5 text-right">{p.quantity}</td>
              <td className="num px-3.5 py-2.5 text-right">
                {p.quantity ? money(p.totalCost / p.quantity) : "—"}
              </td>
              <td className="num px-3.5 py-2.5 text-right">
                {p.markPrice != null ? money(p.markPrice) : "—"}
              </td>
              <td className="px-3.5 py-2.5 text-right">
                <Pnl value={p.realizedPnl} />
              </td>
              <td className="px-3.5 py-2.5 text-right">
                <Pnl value={p.unrealizedPnl} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
