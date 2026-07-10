# AI/MCP layer — design

- Date: 2026-07-10
- Status: approved (runtime / scope / auth chosen), implementing

## Purpose

Expose the tradecore OMS to an LLM (Claude) as a Model Context Protocol server, so a model
can query the book and trade the demo account in natural language — "what do I hold?",
"buy 5 ACME at market", "cancel order 12". This is design-spec §10's AI/MCP phase.

## Decisions

- **Runtime:** Python + FastMCP (the official `mcp` SDK's `FastMCP`), stdio transport. A new
  top-level `mcp/` module, decoupled from the Java backend — it calls the REST API, adding no
  coupling to the modular monolith.
- **Scope:** read + trade. Seven tools (five read, two write), all scoped to the authenticated
  user, mirroring the REST surface.
- **Auth:** demo password-grant. The server obtains a Keycloak token for a configured
  `MCP_USER` (env, default `trader1`/`demo`) via the direct-access grant — identical to
  `scripts/token.sh` and the pre-OIDC BFF — cached and refreshed on expiry, sent as Bearer on
  every backend call. **Dev/local only**, recorded in an ADR; a production MCP would carry the
  end user's own token (the same journey the BFF took to per-user OIDC in ADR-0018).

## Components

- `mcp/tradecore/client.py` — `TradecoreClient`: token acquisition/refresh + typed REST calls
  (`instruments()`, `positions()`, `balances()`, `orders()`, `order(id)`, `submit(...)`,
  `cancel(id)`). One responsibility: HTTP + auth. Injected base URLs + an HTTP client so it is
  unit-testable without a live server.
- `mcp/tradecore/server.py` — FastMCP instance + the seven `@mcp.tool()` functions. Each tool
  is a thin adapter: validate/shape arguments, call the client, format the result for an LLM.
  No HTTP or auth logic here.
- `mcp/tradecore/format.py` — money helpers: every paise figure is presented as both the raw
  integer and a `₹` string so the model never mis-narrates minor units.
- `mcp/pyproject.toml` — `uv`-managed; deps `mcp`, `httpx`; dev `pytest`, `respx`/`pytest-httpx`.
- `mcp/README.md` — run instructions + Claude Desktop / `claude mcp add` config.

## Tools (contract)

| Tool | Args | Returns |
|---|---|---|
| `list_instruments` | – | tradable symbols + names |
| `get_positions` | – | holdings: qty, avg cost, mark, realized/unrealized P&L (paise + ₹) |
| `get_balances` | – | settled / held / available (paise + ₹) |
| `list_orders` | `scope?` | the user's orders newest-first (`scope="all"` needs OPS/ADMIN) |
| `get_order` | `id` | one order |
| `submit_order` | `symbol, side, quantity, type?, price?` | the created order; MARKET may omit price |
| `cancel_order` | `id` | acceptance (trader self-cancel; ops routes to four-eyes server-side) |

Prices are accepted from the model in **rupees** (natural), converted to paise at the tool
edge (`round(rupees * 100)`), matching how the web ticket already behaves.

## Error handling

- The client raises on non-2xx, carrying the backend's RFC 9457 `problem+json` detail.
- Tools surface that as an MCP tool error with the backend's message (e.g. "insufficient
  cash", "Order not cancellable") — **never** a silent empty result. A risk rejection is a
  successful HTTP 201 with `status: REJECTED`; the tool returns it plainly with the reason so
  the model can explain it.

## Testing

- **Unit (pytest):** each tool against a mocked HTTP layer — asserts the request (path, method,
  Bearer header, paise conversion, idempotency key on submit) and the formatted result; error
  mapping for a 4xx and for a REJECTED order.
- **Auth:** token cached until near expiry, re-fetched after.
- **Live:** drive the tools against the running stack — list, submit (LIMIT + unpriced MARKET),
  read back, cancel — and confirm the effects via the REST API.

## Out of scope (this pass)

Streaming/resources/prompts (tools only), multi-user token passthrough (dev password-grant),
and any change to the Java backend — the MCP layer is purely additive over the existing API.
