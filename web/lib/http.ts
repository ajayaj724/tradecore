import { NextResponse } from "next/server";

/**
 * CSRF defence for state-changing route handlers. The BFF acts with an ambient server-side
 * credential, so a cross-site POST must never reach the backend. Browsers send `Origin` on all
 * cross-origin (and same-origin non-GET) fetches, so we require it to match this app's host.
 */
export function rejectCrossOrigin(request: Request): NextResponse | null {
  const origin = request.headers.get("origin");
  const host = request.headers.get("host");
  if (!origin || !host) {
    return NextResponse.json({ error: "Origin header required" }, { status: 403 });
  }
  let originHost: string;
  try {
    originHost = new URL(origin).host;
  } catch {
    return NextResponse.json({ error: "Malformed Origin" }, { status: 403 });
  }
  if (originHost !== host) {
    return NextResponse.json({ error: "Cross-origin request refused" }, { status: 403 });
  }
  return null;
}
