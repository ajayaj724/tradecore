# tradecore OMS — web

The Next.js frontend for the tradecore order-management system. It renders the **Ledger Terminal**
design system (`../design/tradecore-oms/`) and drives the backend `orders` API.

## Run

```bash
# 1. from the repo root, bring up the backend
scripts/up.sh && scripts/run.sh        # backend on :8080, Keycloak on :8081

# 2. then the web app
cd web
npm install
cp .env.example .env.local             # then set AUTH_SECRET (e.g. `openssl rand -base64 32`)
npm run dev                            # http://localhost:3000
```

Sign in as a seeded demo user (`trader1`, `trader2`, `ops1` — password `demo`), then submit a LIMIT
or MARKET order from the ticket; watch it walk the lifecycle rail and settle in the blotter. A
resting order can be cancelled inline.

## Architecture — BFF, no CORS

The browser only ever talks to Next.js. Route handlers under `app/api/orders/**` proxy to the
backend **server-side** (`lib/backend.ts`), attaching the current user's bearer token; no token ever
reaches the client. The backend has no CORS config, so this server-to-server hop is what makes it
work.

**Auth is per-user OIDC** (Auth.js v5 + Keycloak, Authorization Code + PKCE):

- `auth.ts` — the Auth.js config: `tradecore-web` confidential client (registered in
  `../infra/keycloak/tradecore-realm.json`), access/refresh tokens held in the encrypted session
  cookie, refresh rotation in the `jwt` callback (`lib/session-token.ts`), and RP-initiated logout
  so signing out also ends the Keycloak SSO session.
- `proxy.ts` — optimistic gate: pages redirect to `/signin`, API calls get an RFC 9457 401. Real
  enforcement is server-side: `lib/backend.ts` refuses to call out without a session token, and the
  backend validates the JWT on every request.
- `lib/http.ts` `rejectCrossOrigin` — CSRF defence on mutating route handlers, on top of the
  SameSite session cookie.

Env (see `.env.example`): `AUTH_SECRET`, `AUTH_KEYCLOAK_ISSUER`, `AUTH_KEYCLOAK_ID`,
`AUTH_KEYCLOAK_SECRET`, `BACKEND_URL`. Secrets live in `.env.local` (gitignored); the checked-in
client secret in the realm JSON is a local-demo throwaway.

## Test

```bash
npm test          # vitest — token rotation + backend client units
npm run lint
npm run build
```

## Structure

- `auth.ts`, `proxy.ts`, `app/signin/` — OIDC login, session, route protection
- `app/globals.css` — the Ledger Terminal tokens as a Tailwind v4 `@theme`
- `components/TradingScreen.tsx` — the trading surface (header identity, ticket, blotter)
- `components/ui.tsx` — `StatusBadge` (fill-arc), `LifecycleRail` (signature), `StatTile`
- `components/OrderTicket.tsx`, `components/Blotter.tsx`, `components/Positions.tsx` — the
  interactive pieces (ticket, order blotter, holdings with integer P&L)
- `lib/backend.ts` + `lib/backend-client.ts` — server-side backend client (per-user bearer)
- `lib/session-token.ts` — OIDC access-token rotation for the `jwt` callback
- `lib/types.ts` — types mirroring the backend contract; money helpers

## Deliberate follow-ups

The original scaffold limits (session-only blotter, single symbol, no live cash, type not
echoed, undifferentiated roles) are all closed. Still deliberately out of scope:

- **Ops cancel-on-behalf** — an audit/consent workflow, not a UI toggle (ADR-0020).
- **ADMIN role semantics** — `admin1` has no elevated view; unassigned across the system.
- **Reference-price staleness** — unpriced MARKET orders trust the collar, not freshness;
  tightening waits for the feed SLO work (ADR-0021).
