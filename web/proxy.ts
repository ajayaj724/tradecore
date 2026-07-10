import { NextResponse } from "next/server";
import { auth } from "@/auth";

// Optimistic auth gate (Next 16 proxy). Pages bounce to /signin; API calls get an RFC 9457
// 401. Real enforcement stays server-side: lib/backend.ts refuses to call out without a
// session token, and the backend validates the JWT on every request.

export default auth((request) => {
  if (request.auth && !request.auth.error) return NextResponse.next();
  const { nextUrl } = request;
  if (nextUrl.pathname.startsWith("/api/")) {
    return NextResponse.json(
      { type: "about:blank", title: "Unauthorized", status: 401, detail: "Sign in required" },
      { status: 401, headers: { "Content-Type": "application/problem+json" } },
    );
  }
  const signInUrl = new URL("/signin", nextUrl);
  if (nextUrl.pathname !== "/") signInUrl.searchParams.set("callbackUrl", nextUrl.pathname);
  return NextResponse.redirect(signInUrl);
});

export const config = {
  matcher: ["/((?!api/auth|signin|_next|favicon\\.ico|.*\\.svg).*)"],
};
