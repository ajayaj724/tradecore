"""FastMCP server: thin tool adapters over the tradecore REST client.

Each tool validates/shapes arguments, calls the client, and formats the result for an LLM —
no HTTP or auth logic here. Money fields are surfaced as both paise and ₹ so the model
narrates correctly; prices are accepted from the model in rupees and converted at the edge.
"""

from __future__ import annotations

from typing import Any

from mcp.server.fastmcp import FastMCP

from .client import TradecoreClient, TradecoreError
from .format import money, to_paise

mcp = FastMCP("tradecore")
_client = TradecoreClient()


def _order_view(o: dict) -> dict:
    """Shape a backend order for the model — price as paise + ₹."""
    return {
        "id": o["id"],
        "account": o["account"],
        "symbol": o["symbol"],
        "side": o["side"],
        "type": o.get("type"),
        "price": money(o["price"]),
        "quantity": o["quantity"],
        "filledQty": o["filledQty"],
        "status": o["status"],
        "rejectReason": o.get("rejectReason"),
    }


def _position_view(p: dict) -> dict:
    qty = p["quantity"]
    return {
        "symbol": p["symbol"],
        "quantity": qty,
        "avgCost": money(p["totalCost"] // qty if qty else 0),
        "markPrice": money(p["markPrice"]) if p.get("markPrice") is not None else None,
        "realizedPnl": money(p["realizedPnl"]),
        "unrealizedPnl": money(p["unrealizedPnl"]),
    }


@mcp.tool()
async def list_instruments() -> list[dict]:
    """List the instruments that can be traded (symbol and name)."""
    return await _guard(_client.instruments())


@mcp.tool()
async def get_positions() -> list[dict]:
    """The signed-in account's holdings: quantity, average cost, mark price, and P&L."""
    return [_position_view(p) for p in await _guard(_client.positions())]


@mcp.tool()
async def get_balances() -> dict:
    """The signed-in account's cash: settled, held by working orders, and available."""
    b = await _guard(_client.balances())
    return {"account": b["account"], "settled": money(b["settled"]), "held": money(b["held"]), "available": money(b["available"])}


@mcp.tool()
async def list_orders(scope: str | None = None) -> list[dict]:
    """Recent orders, newest first. scope='all' returns every account's orders (needs OPS/ADMIN)."""
    return [_order_view(o) for o in await _guard(_client.orders(scope))]


@mcp.tool()
async def get_order(order_id: int) -> dict:
    """Look up a single order by its id."""
    return _order_view(await _guard(_client.order(order_id)))


@mcp.tool()
async def submit_order(
    symbol: str, side: str, quantity: int, type: str = "LIMIT", price: float | None = None
) -> dict:
    """Submit an order. side BUY/SELL; type LIMIT or MARKET. price is in RUPEES per share and is
    required for LIMIT; a MARKET order may omit it (the backend derives a protective cap)."""
    order: dict[str, Any] = {"symbol": symbol.upper(), "side": side.upper(), "quantity": quantity, "type": type.upper()}
    if price is not None:
        order["price"] = to_paise(price)
    return _order_view(await _guard(_client.submit(order)))


@mcp.tool()
async def cancel_order(order_id: int) -> dict:
    """Cancel a working order. For a trader this cancels immediately; for ops it requests a
    four-eyes approval server-side."""
    return _order_view(await _guard(_client.cancel(order_id)))


async def _guard(awaitable):
    """Surface backend failures as clear MCP tool errors — never a silent empty result."""
    try:
        return await awaitable
    except TradecoreError as e:
        raise RuntimeError(e.detail) from e


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
