"use client";

import type { HealthReport } from "@/lib/types";
import { rupees } from "@/lib/types";

/** Admin-only: read-model health from the reconciliation module (drift 0 = projections honest). */
export function HealthPanel({ report }: { report: HealthReport }) {
  const healthy = report.driftPairs === 0;
  return (
    <div className="overflow-hidden rounded-[4px] border border-line bg-panel">
      <div className="flex items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <span
          className={`rounded-[3px] border px-2 py-0.5 text-[10px] font-bold ${
            healthy ? "border-[#c4ded4] bg-buybg text-buy" : "border-[#e8c9c3] bg-sellbg text-sell"
          }`}
        >
          {healthy ? "RECONCILED" : `DRIFT · ${report.driftPairs}`}
        </span>
        <span className="text-[11px] text-ink3">
          ledger vs risk projections, checked across the configured universe
        </span>
      </div>
      <table className="w-full border-collapse text-xs">
        <thead>
          <tr className="bg-[#f1eee6] text-[9px] uppercase tracking-[0.1em] text-ink3">
            <th className="px-3.5 py-2.5 text-left font-bold">Account</th>
            <th className="px-3.5 py-2.5 text-right font-bold">Equity</th>
            <th className="px-3.5 py-2.5 text-right font-bold">Cash drift</th>
            <th className="px-3.5 py-2.5 text-right font-bold">Drifted pairs</th>
          </tr>
        </thead>
        <tbody>
          {report.accounts.map((a) => (
            <tr key={a.account} className="border-t border-line">
              <td className="px-3.5 py-2.5 font-semibold">{a.account}</td>
              <td className="num px-3.5 py-2.5 text-right">{rupees(a.equity)}</td>
              <td className={`num px-3.5 py-2.5 text-right ${a.cashDrift === 0 ? "text-ink3" : "text-sell"}`}>
                {rupees(a.cashDrift)}
              </td>
              <td className={`num px-3.5 py-2.5 text-right ${a.driftedPairs === 0 ? "text-ink3" : "text-sell"}`}>
                {a.driftedPairs}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
