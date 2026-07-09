import { NextResponse } from "next/server";
import { backend } from "@/lib/backend";

export async function POST(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const result = await backend.cancel(Number(id));
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
