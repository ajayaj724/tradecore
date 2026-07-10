"use client";

import type { CancelRequestInfo } from "@/lib/types";

/** Pending four-eyes cancel requests. The requester's own Approve is disabled (ADR-0024). */
export function Approvals({
  requests,
  me,
  canDecide,
  onDecide,
}: {
  requests: CancelRequestInfo[];
  me: string;
  canDecide: boolean;
  onDecide: (id: number, action: "approve" | "decline") => void;
}) {
  return (
    <div className="overflow-hidden rounded-[4px] border border-line bg-panel">
      <table className="w-full border-collapse text-xs">
        <thead>
          <tr className="bg-[#f1eee6] text-[9px] uppercase tracking-[0.1em] text-ink3">
            <th className="px-3.5 py-2.5 text-left font-bold">Request</th>
            <th className="px-3.5 py-2.5 text-left font-bold">Order</th>
            <th className="px-3.5 py-2.5 text-left font-bold">Account</th>
            <th className="px-3.5 py-2.5 text-left font-bold">Symbol</th>
            <th className="px-3.5 py-2.5 text-left font-bold">Requested by</th>
            <th className="px-3.5 py-2.5" />
          </tr>
        </thead>
        <tbody>
          {requests.length === 0 && (
            <tr>
              <td colSpan={6} className="px-3.5 py-6 text-center text-ink3">
                No pending cancellation requests.
              </td>
            </tr>
          )}
          {requests.map((r) => {
            const own = r.requestedBy === me;
            return (
              <tr key={r.id} className="border-t border-line">
                <td className="num px-3.5 py-2.5 text-ink3">#{r.id}</td>
                <td className="num px-3.5 py-2.5">#{r.orderId}</td>
                <td className="px-3.5 py-2.5 font-semibold">{r.account}</td>
                <td className="px-3.5 py-2.5 font-bold">{r.symbol}</td>
                <td className="px-3.5 py-2.5">{r.requestedBy}</td>
                <td className="px-3.5 py-2.5 text-right">
                  {canDecide && (
                    <span className="inline-flex gap-1.5">
                      <button
                        onClick={() => onDecide(r.id, "approve")}
                        disabled={own}
                        title={own ? "Four-eyes: a different ops user must approve" : undefined}
                        className="rounded-[3px] border border-[#c4ded4] px-2 py-1 text-[10.5px] font-semibold text-buy disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        Approve
                      </button>
                      <button
                        onClick={() => onDecide(r.id, "decline")}
                        className="rounded-[3px] border border-[#e0bdb6] px-2 py-1 text-[10.5px] font-semibold text-sell"
                      >
                        Decline
                      </button>
                    </span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
