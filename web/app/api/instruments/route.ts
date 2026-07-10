import { NextResponse } from "next/server";
import { backend } from "@/lib/backend";

export async function GET() {
  const result = await backend.instruments();
  return NextResponse.json(result.data ?? [], { status: result.status });
}
