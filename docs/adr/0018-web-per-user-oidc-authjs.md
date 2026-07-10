# 0018: Per-user OIDC login for the web BFF (Auth.js v5 + Keycloak)

- Status: accepted
- Date: 2026-07-10

## Context

The Next.js frontend (`web/`) shipped as a scaffold whose BFF authenticated with **one
shared Keycloak token** obtained via the password grant as `trader1`, with a hardcoded
`"demo"` password default. The commit security review flagged both
(authentication-bypass, hardcoded-credentials): every browser user acted as trader1, and
no real login existed. The backend already validates per-user JWTs and books orders under
the token's `preferred_username`, so the missing piece was purely on the web tier.

## Decision

Replace the shared-token BFF client with **per-user OIDC login**:

- **Authorization Code + PKCE** against the local Keycloak realm, via a new confidential
  client `tradecore-web` registered in `infra/keycloak/tradecore-realm.json` (secret is a
  local-demo throwaway, same class as the seeded demo users; realm file is gitleaks-allowlisted).
- **Auth.js v5 (`next-auth@5.0.0-beta.31`)** implements the flow. The v5 beta is the
  official install channel for the App Router and the only line whose peer range includes
  Next 16 (v4 does not support the App Router). Pinning a beta contradicts the
  latest-stable-GA rule, hence this ADR: accepted as the vendor-recommended current line,
  revisit when v5 goes GA.
- The user's access/refresh tokens live **only in the encrypted session cookie** (JWE);
  `lib/session-token.ts` rotates the access token via the refresh grant inside the `jwt`
  callback, and a refresh failure marks the session so the proxy forces re-login.
- `lib/backend.ts` forwards the **current user's** token per request; with no session it
  returns 401 without calling out. No service credential exists in the web tier anymore.
- **RP-initiated logout** (`id_token_hint`) ends the Keycloak SSO session on sign-out so
  the next login genuinely re-authenticates (demo user switching, kiosk safety).
- Next 16's `proxy.ts` provides the optimistic gate (redirect pages to `/signin`,
  RFC 9457 401 for APIs); real enforcement stays server-side (BFF token check + backend
  JWT validation). CSRF `rejectCrossOrigin` stays on mutating route handlers.
- Compose maps Keycloak on both loopback stacks (`127.0.0.1` and `[::1]`) because Node
  ≥20 resolves `localhost` to `::1` first; exposure remains loopback-only.

## Consequences

- Both security findings are closed structurally: there is no shared identity and no
  credential literal to leak. Orders are booked under the real logged-in user.
- `scripts/token.sh` / `scripts/api.sh` keep using the `tradecore-api` password-grant
  client — unchanged, still the right tool for CLI/demo tooling.
- New envs for `web/`: `AUTH_SECRET`, `AUTH_KEYCLOAK_ISSUER`, `AUTH_KEYCLOAK_ID`,
  `AUTH_KEYCLOAK_SECRET` (`.env.local`, gitignored).
- When Auth.js v5 reaches GA, bump off the beta; no API changes expected (same major).
