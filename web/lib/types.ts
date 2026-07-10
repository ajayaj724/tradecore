export type Side = "BUY" | "SELL";
export type OrderType = "LIMIT" | "MARKET";
export type OrderStatus =
  | "NEW"
  | "ACCEPTED"
  | "PARTIALLY_FILLED"
  | "FILLED"
  | "CANCELLED"
  | "REJECTED";

/** Mirrors the backend OrderResponse. Money is minor units (paise); format only at the edge. */
export interface OrderResponse {
  id: number;
  account: string;
  symbol: string;
  side: Side;
  type: OrderType;
  price: number;
  quantity: number;
  filledQty: number;
  status: OrderStatus;
  rejectReason: string | null;
}

/** A tradable instrument from the backend's reference data. */
export interface InstrumentInfo {
  symbol: string;
  name: string;
}

/** Mirrors the backend PositionResponse. All paise/integer; avg cost is derived for display. */
export interface PositionInfo {
  symbol: string;
  quantity: number;
  totalCost: number;
  markPrice: number | null;
  realizedPnl: number;
  unrealizedPnl: number;
}

/** Mirrors the backend CancelRequestResponse (four-eyes ops cancellation). */
export interface CancelRequestInfo {
  id: number;
  orderId: number;
  account: string;
  symbol: string;
  requestedBy: string;
  status: string;
}

/** Mirrors the backend ReconciliationReport. Paise; driftPairs 0 = healthy. */
export interface HealthReport {
  driftPairs: number;
  accounts: { account: string; equity: number; cashDrift: number; driftedPairs: number }[];
}

/** Mirrors the backend CashBalance. Paise; available = settled - held. */
export interface CashBalance {
  account: string;
  settled: number;
  held: number;
  available: number;
}

export interface SubmitOrderRequest {
  symbol: string;
  side: Side;
  /** Paise. Omit for MARKET — the backend derives a collared protective cap. */
  price?: number;
  quantity: number;
  type?: OrderType;
}

/** A working order can still be cancelled; everything else is terminal. */
export const isWorking = (s: OrderStatus) => s === "ACCEPTED" || s === "PARTIALLY_FILLED";
export const isTerminal = (s: OrderStatus) =>
  s === "FILLED" || s === "CANCELLED" || s === "REJECTED";

/** paise → ₹ with Indian grouping. Display only. */
export const rupees = (paise: number) =>
  "₹" +
  (paise / 100).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
