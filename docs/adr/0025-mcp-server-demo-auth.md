# 0025: MCP server auth — demo password-grant, dev-only

- Status: accepted (local-only)
- Date: 2026-07-10

## Context

The AI/MCP layer (`mcp/`) is a FastMCP server that lets an LLM trade the OMS through its REST
API. Every backend call needs a Keycloak-validated JWT. The server runs as a local process
(stdio) driven by a desktop LLM; it has no browser and no interactive login to ride.

## Decision

The MCP server authenticates with the **direct-access (password) grant** for a configured
demo user (`MCP_USER`/`MCP_PASSWORD`, default `trader1`/`demo`) against the `tradecore-api`
client — identical to `scripts/token.sh` and the pre-OIDC BFF. The token is cached and
re-fetched near expiry; it rides as a Bearer on every call.

This is **local development only**, exactly like [ADR-0002](0002-actuator-prometheus-scrape-exposure.md)'s
prometheus concession. A production MCP server would forward the *end user's own* token
(obtained by the MCP host), the same journey the BFF took to per-user OIDC in
[ADR-0018](0018-web-per-user-oidc-authjs.md) — the OMS is already a resource server that
validates whatever JWT arrives, so no backend change is needed to get there.

Rejected: baking a per-tool `access_token` argument now. MCP token passthrough from desktop
hosts is not yet ergonomic, and a fixed demo identity is the honest scope for a local demo —
made explicit here rather than disguised as multi-user.

## Consequences

- The whole MCP surface acts as one demo trader; switching identity = changing an env var and
  restarting. Adequate for demonstrating the OMS to an LLM, not for real multi-user use.
- No new attack surface on the backend: the MCP server is just another OAuth2 client using an
  already-supported grant, confined to local use.
