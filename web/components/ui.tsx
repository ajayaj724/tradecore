import type { OrderStatus } from "@/lib/types";

type BadgeStyle = { label: string; box: string; ring: string; arc?: (pct: number) => string };

const STATUS: Record<OrderStatus, BadgeStyle> = {
  NEW: { label: "New", box: "text-ink2 bg-[#f0ede5] border-line", ring: "border-ink3" },
  ACCEPTED: { label: "Accepted", box: "text-accepted bg-acceptedbg border-[#cbd9e6]", ring: "border-accepted" },
  PARTIALLY_FILLED: {
    label: "Partial",
    box: "text-amber bg-amberbg border-[#e7d5af]",
    ring: "border-amber",
    arc: (pct) => `conic-gradient(var(--color-amber) ${pct}%, transparent 0)`,
  },
  FILLED: {
    label: "Filled",
    box: "text-buy bg-buybg border-[#c4ded4]",
    ring: "border-buy",
    arc: () => "var(--color-buy)",
  },
  CANCELLED: { label: "Cancelled", box: "text-muted bg-mutedbg border-[#dad5c8]", ring: "border-muted" },
  REJECTED: { label: "Rejected", box: "text-sell bg-sellbg border-[#e8c9c3]", ring: "border-sell" },
};

/** Status badge whose inner arc fills the way the order does. */
export function StatusBadge({ status, fill = 0 }: { status: OrderStatus; fill?: number }) {
  const s = STATUS[status];
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-[3px] border px-2 py-0.5 text-[11px] font-semibold ${s.box}`}
    >
      <span
        className={`h-[11px] w-[11px] shrink-0 rounded-full border-[1.5px] ${s.ring}`}
        style={s.arc ? { background: s.arc(Math.round(fill * 100)) } : undefined}
      />
      {s.label}
    </span>
  );
}

const RAIL = ["NEW", "ACCEPTED", "PARTIALLY_FILLED", "FILLED"] as const;
const RAIL_LABEL = ["New", "Accepted", "Partially filled", "Filled"];

/** The signature: an order's sealed journey. Cancel/reject are terminal off-ramps. */
export function LifecycleRail({ status, fill = 0 }: { status: OrderStatus; fill?: number }) {
  const terminalOff = status === "CANCELLED" || status === "REJECTED";
  const reachedIdx = status === "REJECTED" ? 0 : status === "CANCELLED" ? 1 : RAIL.indexOf(status as (typeof RAIL)[number]);

  const node = (label: string, state: "done" | "current" | "future" | "off", pct = 0) => (
    <div className="flex w-[100px] shrink-0 flex-col items-center gap-2">
      <span
        className={`grid h-6 w-6 place-items-center rounded-full border-[1.75px] ${
          state === "off"
            ? status === "REJECTED"
              ? "border-sell"
              : "border-muted"
            : state === "future"
              ? "border-ink3 bg-paper"
              : state === "current"
                ? "border-amber"
                : "border-buy bg-buy"
        }`}
        style={state === "current" ? { background: `conic-gradient(var(--color-amber) ${pct}%, transparent 0)` } : undefined}
      >
        {state === "done" && <span className="h-[7px] w-[7px] rounded-full bg-paper" />}
        {state === "off" && (
          <span className={`text-[12px] font-extrabold leading-none ${status === "REJECTED" ? "text-sell" : "text-muted"}`}>✕</span>
        )}
      </span>
      <span className={`text-center text-[9px] font-semibold uppercase tracking-[0.06em] ${state === "future" ? "text-ink3" : "text-ink"}`}>
        {label}
      </span>
    </div>
  );

  const seg = (done: boolean) => (
    <div className={`mt-[11px] h-0.5 min-w-[20px] flex-1 rounded ${done ? "bg-ink" : "bg-line2"}`} />
  );

  const stops: React.ReactNode[] = [];
  const upto = terminalOff ? reachedIdx : RAIL.length - 1;
  for (let i = 0; i <= upto; i++) {
    if (i > 0) stops.push(<span key={`s${i}`} className="contents">{seg(i <= reachedIdx)}</span>);
    const state = i < reachedIdx ? "done" : i === reachedIdx && status === "PARTIALLY_FILLED" ? "current" : i <= reachedIdx ? "done" : "future";
    stops.push(<span key={`n${i}`} className="contents">{node(RAIL_LABEL[i], state as "done" | "current" | "future", Math.round(fill * 100))}</span>);
  }
  if (terminalOff) {
    stops.push(<span key="soff" className="contents">{seg(true)}</span>);
    stops.push(<span key="noff" className="contents">{node(status === "REJECTED" ? "Rejected" : "Cancelled", "off")}</span>);
  }
  return <div className="flex items-start">{stops}</div>;
}

export function StatTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[3px] border border-line bg-panel px-3 py-2.5">
      <div className="eyebrow">{label}</div>
      <div className="num mt-0.5 text-base font-semibold">{value}</div>
    </div>
  );
}
