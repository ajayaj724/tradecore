"use client";

import type { OrderResponse } from "@/lib/types";
import { isWorking, rupees } from "@/lib/types";
import { StatusBadge } from "./ui";

export function Blotter({
  orders,
  selectedId,
  onSelect,
  onCancel,
  showAccount = false,
  canCancel = true,
}: {
  orders: OrderResponse[];
  selectedId: number | null;
  onSelect: (id: number) => void;
  onCancel: (id: number) => void;
  showAccount?: boolean;
  canCancel?: boolean;
}) {
  return (
    <div className="overflow-hidden rounded-[4px] border border-line bg-panel">
      <table className="w-full border-collapse text-xs">
        <thead>
          <tr className="bg-[#f1eee6] text-[9px] uppercase tracking-[0.1em] text-ink3">
            <th className="px-3.5 py-2.5 text-left font-bold">Order</th>
            {showAccount && <th className="px-3.5 py-2.5 text-left font-bold">Account</th>}
            <th className="px-3.5 py-2.5 text-left font-bold">Symbol</th>
            <th className="px-3.5 py-2.5 text-left font-bold">Side</th>
            <th className="px-3.5 py-2.5 text-left font-bold">Type</th>
            <th className="px-3.5 py-2.5 text-right font-bold">Price</th>
            <th className="px-3.5 py-2.5 text-right font-bold">Filled</th>
            <th className="px-3.5 py-2.5 text-left font-bold">Status</th>
            <th className="px-3.5 py-2.5" />
          </tr>
        </thead>
        <tbody>
          {orders.length === 0 && (
            <tr>
              <td colSpan={showAccount ? 9 : 8} className="px-3.5 py-8 text-center text-ink3">
                {showAccount ? "No orders on the book." : "No orders yet. Place one from the ticket."}
              </td>
            </tr>
          )}
          {orders.map((o) => (
            <tr
              key={o.id}
              onClick={() => onSelect(o.id)}
              className={`cursor-pointer border-t border-line ${selectedId === o.id ? "bg-goldbg" : ""}`}
            >
              <td className="num px-3.5 py-2.5 text-ink3">#{o.id}</td>
              {showAccount && <td className="px-3.5 py-2.5 font-semibold">{o.account}</td>}
              <td className="px-3.5 py-2.5 font-bold">{o.symbol}</td>
              <td className={`px-3.5 py-2.5 font-bold ${o.side === "BUY" ? "text-buy" : "text-sell"}`}>{o.side}</td>
              <td className="px-3.5 py-2.5 text-ink2">{o.type}</td>
              <td className="num px-3.5 py-2.5 text-right">{rupees(o.price).replace("₹", "")}</td>
              <td className="num px-3.5 py-2.5 text-right">
                {o.filledQty} / {o.quantity}
              </td>
              <td className="px-3.5 py-2.5">
                <StatusBadge status={o.status} fill={o.quantity ? o.filledQty / o.quantity : 0} />
              </td>
              <td className="px-3.5 py-2.5 text-right">
                {isWorking(o.status) && canCancel ? (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      onCancel(o.id);
                    }}
                    className="rounded-[3px] border border-[#e0bdb6] px-2 py-1 text-[10.5px] font-semibold text-sell"
                  >
                    Cancel
                  </button>
                ) : (
                  <span className="text-ink3">—</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
