# tradecore MCP server

An [MCP](https://modelcontextprotocol.io) server that exposes the tradecore OMS to an LLM, so a
model can query the book and trade the demo account in natural language. FastMCP (Python),
stdio transport; it calls the backend's REST API — no coupling to the modular monolith.

## Tools

| Tool | Purpose |
|---|---|
| `list_instruments` | tradable symbols + names |
| `get_positions` | holdings: qty, avg cost, mark, realized/unrealized P&L |
| `get_balances` | settled / held / available cash |
| `list_orders` | your orders newest-first (`scope="all"` needs OPS/ADMIN) |
| `get_order` | one order by id |
| `submit_order` | place a LIMIT or MARKET order (price in **rupees**; MARKET may omit it) |
| `cancel_order` | cancel a working order |

Money is returned as both `paise` (the OMS's integer minor unit) and a `₹` display string.

## Run

The backend must be up (`scripts/up.sh && scripts/run.sh` from the repo root).

```bash
cd mcp
uv sync
uv run pytest          # unit tests
uv run tradecore-mcp   # or: uv run python -m tradecore.server  (stdio server)
```

## Auth (dev-only)

The server obtains a Keycloak token for a configured demo user via the direct-access
(password) grant — the same mechanism as `scripts/token.sh` — and sends it as a Bearer on
every call. **Local development only** (see [ADR-0025](../docs/adr/0025-mcp-server-demo-auth.md));
a production MCP would forward the end user's own token.

Configure via env (all optional, defaults shown):

```
BACKEND_URL=http://localhost:8080
KEYCLOAK_URL=http://localhost:8081
KEYCLOAK_REALM=tradecore
KEYCLOAK_CLIENT=tradecore-api
MCP_USER=trader1
MCP_PASSWORD=demo
```

## Use from Claude

Register the server (backend running first):

```bash
claude mcp add tradecore -- uv --directory /ABSOLUTE/PATH/tradecore/mcp run tradecore-mcp
```

Then ask Claude things like *"what do I hold?"*, *"buy 5 ACME at market"*, *"cancel order 3"*.
