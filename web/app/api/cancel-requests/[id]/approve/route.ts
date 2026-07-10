import { NextResponse } from "next/server";
import { backend } from "@/lib/backend";
import { rejectCrossOrigin } from "@/lib/http";

export async function POST(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const blocked = rejectCrossOrigin(request);
  if (blocked) return blocked;
  const { id } = await params;
  const result = await backend.approveCancelRequest(Number(id));
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
