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
npm run dev                            # http://localhost:3000
```

Submit a LIMIT or MARKET order from the ticket; watch it walk the lifecycle rail and settle in the
blotter. A resting order can be cancelled inline.

## Architecture — BFF, no CORS

The browser only ever talks to Next.js. Route handlers under `app/api/orders/**` proxy to the
backend **server-side** (`lib/backend.ts`), attaching a Keycloak bearer token that never reaches the
client. The backend has no CORS config, so this server-to-server hop is what makes it work.

**Auth is dev-only:** the BFF fetches a token via Keycloak's password grant as a seeded demo user
(`trader1/demo`, same as `scripts/token.sh`), configured in `.env` (see `.env.example`). Swap this
for a proper OIDC login (e.g. per-user Authorization Code flow) before any non-local deployment.

## Structure

- `app/globals.css` — the Ledger Terminal tokens as a Tailwind v4 `@theme`
- `components/ui.tsx` — `StatusBadge` (fill-arc), `LifecycleRail` (signature), `StatTile`
- `components/OrderTicket.tsx`, `components/Blotter.tsx` — the interactive pieces
- `lib/backend.ts` — server-side backend client + token cache
- `lib/types.ts` — types mirroring the backend contract; money helpers

## Scaffold limits (deliberate follow-ups)

- **Blotter shows this session's orders.** The backend exposes `GET /orders/{id}` but no list
  endpoint, so the app tracks the ids it submitted and polls each. A `GET /api/v1/orders` on the
  backend would let it load history.
- **Single symbol (ACME)** and **no live cash figure** — there's no instruments or balances endpoint
  yet; "Reserved" is derived client-side from working orders.
- Order **type isn't echoed** by the API response, so the blotter shows `—` for it.
