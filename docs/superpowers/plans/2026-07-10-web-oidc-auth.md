# Handoff: per-user OIDC auth for the web frontend (enterprise-grade)

**Written 2026-07-10 for a fresh session.** Goal: replace the dev single-service-token BFF with real
per-user OIDC login (Auth.js v5 + Keycloak Authorization Code + PKCE), closing two security-review
findings. All facts below are verified against the running code.

## Why / the findings
The commit security review flagged three issues in `web/`. **CSRF is already fixed** (`web/lib/http.ts`
`rejectCrossOrigin`, applied to both POST route handlers; verified same-origin→201, cross-origin→403).
The remaining two are one fix:
- **authentication-bypass** — `web/lib/backend.ts` uses ONE shared Keycloak token (password grant as
  `trader1`) for every request, so the browser user is always trader1. No per-user auth.
- **hardcoded-credentials** — the `?? "demo"` password default in the same file.

Both vanish when each request forwards the *logged-in user's* token instead of a shared service token.

## Current state (all on `main`, HEAD `3cc4811`, CI green, repo public)
- `web/` — runnable Next.js 16 / React 19 / Tailwind v4 app (BFF). `cd web && npm run dev` → :3000.
  Verified live end-to-end (browser → BFF → Keycloak → backend; created order #10).
- `design/tradecore-oms/` — the Ledger Terminal design system (also in claude.ai/design). Style the
  sign-in page from it.
- Backend on :8080, Keycloak on :8081 (realm `tradecore`). Bring up with `scripts/up.sh && scripts/run.sh`.

## Key facts
- **Keycloak realm file:** `infra/keycloak/tradecore-realm.json` (compose imports it via `--import-realm`).
  The existing `tradecore-api` client is defined there — model the new web client on it.
- Existing client `tradecore-api` = direct-access (password) grant, used by `scripts/token.sh` and the
  current BFF. **Leave it**; add a NEW client.
- Issuer: `http://localhost:8081/realms/tradecore`. Demo users: trader1/trader2/ops1/admin1, pw `demo`
  (allowlisted as non-secrets in `.gitleaks.toml` — keep them there; don't commit real secrets).

## Plan
1. **Register a web client** in `infra/keycloak/tradecore-realm.json`: `tradecore-web`, confidential
   (`publicClient: false`), `standardFlowEnabled: true`, PKCE (`pkce.code.challenge.method: S256`),
   redirect URI `http://localhost:3000/api/auth/callback/keycloak`, web origin `http://localhost:3000`,
   a `secret`. Re-import: `scripts/down.sh --wipe && scripts/up.sh` (or restart Keycloak). Verify the
   client exists at :8081 admin (admin/admin).
2. **Auth.js v5:** `cd web && npm i next-auth@beta`. Add `web/auth.ts` (Keycloak provider: issuer,
   clientId `tradecore-web`, clientSecret from env), `web/app/api/auth/[...nextauth]/route.ts`.
3. **Token passthrough:** in the `jwt` callback store `account.access_token` (+ `expires_at` and
   `refresh_token`); refresh via the Keycloak token endpoint when expired. Expose it through `session`
   (or read it server-side via `auth()`).
4. **`middleware.ts`** — protect all routes except the sign-in + `/api/auth/*`; redirect unauthenticated
   users to a Ledger-Terminal-styled sign-in page.
5. **Rewrite `web/lib/backend.ts`** — delete the password grant, the `DEMO_USER/DEMO_PASSWORD` env, and
   the `"demo"` default. Instead read the current user's access token (from `auth()`/session) and forward
   it as the Bearer. Each call now acts as the logged-in user → closes both findings. Keep sending the
   `Idempotency-Key` on POST.
6. **Keep** `rejectCrossOrigin` on the mutating routes; Auth.js adds SameSite cookies on top.
7. Update `web/README.md` (remove the "dev password grant" caveat; document the OIDC flow + the
   `tradecore-web` client + `AUTH_SECRET`/`KEYCLOAK_CLIENT_SECRET` env). Update `web/.env.example`.

## Verify (don't skip)
- `npm run build` compiles.
- Login flow: unauthenticated → redirected to sign-in → Keycloak login (trader1/demo) → back to the app.
  Playwright can drive the redirect (webapp-testing skill / playwright MCP).
- Submit an order → it's created as the *logged-in* user (check `account` in the response matches).
- Cross-origin POST still 403 (CSRF intact).

## Gotchas
- **Next.js 16:** dynamic route `params` is a `Promise` (`await params`). Tailwind is **v4** (tokens live
  in `web/app/globals.css` `@theme`, not a config file). Route handlers are servlet-style `Request`.
- Ledger Terminal is a committed **light** aesthetic — style the sign-in page to match (no dark mode).
- Don't commit the Keycloak client secret or `AUTH_SECRET` — env only; `.env.local` is gitignored.
- Background procs from the previous session may still be running: Next dev (:3000) and the backend
  (was PID 69342). Restart cleanly if unsure (`scripts/run.sh`, `npm run dev`).
- Follow the repo workflow: TDD where it fits, `scripts/gate.sh` before any backend commit (the realm
  JSON is not built, but if you touch Java, gate it), conventional commits, feature branch + `--no-ff`.
