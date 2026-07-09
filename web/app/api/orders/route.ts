import { NextResponse } from "next/server";
import { backend } from "@/lib/backend";
import type { SubmitOrderRequest } from "@/lib/types";

export async function POST(request: Request) {
  const body = (await request.json()) as SubmitOrderRequest;
  const result = await backend.submit(body);
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
