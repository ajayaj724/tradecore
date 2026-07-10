import { NextResponse } from "next/server";
import { backend } from "@/lib/backend";
import { rejectCrossOrigin } from "@/lib/http";
import type { SubmitOrderRequest } from "@/lib/types";

export async function GET() {
  const result = await backend.list();
  return NextResponse.json(result.data ?? [], { status: result.status });
}

export async function POST(request: Request) {
  const blocked = rejectCrossOrigin(request);
  if (blocked) return blocked;
  const body = (await request.json()) as SubmitOrderRequest;
  const result = await backend.submit(body);
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
