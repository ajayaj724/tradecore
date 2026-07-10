import { NextResponse } from "next/server";
import { backend } from "@/lib/backend";
import { rejectCrossOrigin } from "@/lib/http";
import type { SubmitOrderRequest } from "@/lib/types";

export async function GET(request: Request) {
  const scope = new URL(request.url).searchParams.get("scope");
  const result = await backend.list(scope === "all" ? "all" : undefined);
  return NextResponse.json(result.data ?? [], { status: result.status });
}

export async function POST(request: Request) {
  const blocked = rejectCrossOrigin(request);
  if (blocked) return blocked;
  const body = (await request.json()) as SubmitOrderRequest;
  const result = await backend.submit(body);
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
