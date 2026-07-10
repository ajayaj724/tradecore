import NextAuth from "next-auth";
import type { JWT } from "next-auth/jwt";
import Keycloak from "next-auth/providers/keycloak";
import { rotateToken, type RefreshConfig, type TokenBundle } from "@/lib/session-token";

// Per-user OIDC login for the BFF: Authorization Code + PKCE against the local Keycloak
// realm. The user's access token lives only inside the encrypted session cookie (JWE);
// lib/backend.ts forwards it to the backend, and the browser never sees a bearer token.

const issuer = process.env.AUTH_KEYCLOAK_ISSUER ?? "http://localhost:8081/realms/tradecore";

const refresh: RefreshConfig = {
  tokenEndpoint: `${issuer}/protocol/openid-connect/token`,
  clientId: process.env.AUTH_KEYCLOAK_ID ?? "tradecore-web",
  clientSecret: process.env.AUTH_KEYCLOAK_SECRET ?? "",
};

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Keycloak({ issuer, clientId: refresh.clientId, clientSecret: refresh.clientSecret }),
  ],
  pages: { signIn: "/signin" },
  callbacks: {
    async jwt({ token, account, profile }) {
      const rotated = await rotateToken(token as TokenBundle, account, refresh);
      // The backend books orders under preferred_username — keep it so the UI can show
      // exactly the identity the orders belong to.
      if (typeof profile?.preferred_username === "string") {
        rotated.username = profile.preferred_username;
      }
      return rotated as JWT;
    },
    session({ session, token }) {
      const t = token as TokenBundle;
      session.accessToken = t.error ? undefined : t.access_token;
      session.username = typeof t.username === "string" ? t.username : undefined;
      session.error = t.error;
      return session;
    },
  },
  events: {
    // RP-initiated logout: also end the Keycloak SSO session, otherwise the next sign-in
    // silently re-authenticates as the previous user. Best-effort by design — a Keycloak
    // hiccup must not stop the local session from clearing — but never silent.
    async signOut(message) {
      const token = "token" in message ? (message.token as TokenBundle | null) : null;
      if (typeof token?.id_token !== "string") return;
      const url = new URL(`${issuer}/protocol/openid-connect/logout`);
      url.searchParams.set("id_token_hint", token.id_token);
      const res = await fetch(url, { cache: "no-store" });
      if (!res.ok) console.error(`Keycloak SSO logout failed (${res.status})`);
    },
  },
});

declare module "next-auth" {
  interface Session {
    accessToken?: string;
    username?: string;
    error?: "RefreshTokenError";
  }
}
