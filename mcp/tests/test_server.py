"""Tool-adapter tests: shape/format logic over a fake client (no HTTP)."""

import pytest

from tradecore import server
from tradecore.client import TradecoreError


class FakeClient:
    def __init__(self):
        self.submitted = None
        self.cancelled = None

    async def instruments(self):
        return [{"symbol": "ACME", "name": "Acme Corp"}]

    async def positions(self):
        return [{"symbol": "INFY", "quantity": 10, "totalCost": 1500000, "markPrice": 149000,
                 "realizedPnl": -3000, "unrealizedPnl": -10000}]

    async def balances(self):
        return {"account": "trader1", "settled": 100000000, "held": 300000, "available": 99700000}

    async def orders(self, scope=None):
        self.scope = scope
        return [{"id": 2, "account": "trader1", "symbol": "ACME", "side": "BUY", "type": "MARKET",
                 "price": 10500, "quantity": 5, "filledQty": 0, "status": "CANCELLED", "rejectReason": None}]

    async def order(self, order_id):
        return {"id": order_id, "account": "trader1", "symbol": "ACME", "side": "SELL", "type": "LIMIT",
                "price": 10000, "quantity": 1, "filledQty": 1, "status": "FILLED", "rejectReason": None}

    async def submit(self, order):
        self.submitted = order
        return {"id": 9, "account": "trader1", "symbol": order["symbol"], "side": order["side"],
                "type": order["type"], "price": order.get("price", 0), "quantity": order["quantity"],
                "filledQty": 0, "status": "ACCEPTED", "rejectReason": None}

    async def cancel(self, order_id):
        self.cancelled = order_id
        return {"id": order_id, "account": "trader1", "symbol": "ACME", "side": "BUY", "type": "LIMIT",
                "price": 5000, "quantity": 1, "filledQty": 0, "status": "ACCEPTED", "rejectReason": None}


@pytest.fixture
def fake(monkeypatch):
    c = FakeClient()
    monkeypatch.setattr(server, "_client", c)
    return c


async def _call(tool, **kwargs):
    # FastMCP's @mcp.tool() registers the tool and returns the original coroutine function.
    return await tool(**kwargs)


async def test_get_positions_derives_avg_cost_and_pnl_display(fake):
    [pos] = await _call(server.get_positions)
    assert pos["avgCost"] == {"paise": 150000, "display": "₹1,500.00"}
    assert pos["markPrice"]["display"] == "₹1,490.00"
    assert pos["unrealizedPnl"]["display"] == "-₹100.00"


async def test_get_balances_formats_money(fake):
    b = await _call(server.get_balances)
    assert b["available"] == {"paise": 99700000, "display": "₹9,97,000.00"}


async def test_submit_converts_rupee_price_to_paise_and_uppercases(fake):
    out = await _call(server.submit_order, symbol="acme", side="buy", quantity=5, type="limit", price=100.00)
    assert fake.submitted["price"] == 10000
    assert fake.submitted["symbol"] == "ACME"
    assert fake.submitted["side"] == "BUY"
    assert out["status"] == "ACCEPTED"


async def test_market_order_may_omit_price(fake):
    await _call(server.submit_order, symbol="ACME", side="BUY", quantity=2, type="MARKET")
    assert "price" not in fake.submitted


async def test_list_orders_passes_scope(fake):
    await _call(server.list_orders, scope="all")
    assert fake.scope == "all"


async def test_cancel_calls_backend(fake):
    await _call(server.cancel_order, order_id=12)
    assert fake.cancelled == 12


async def test_tool_error_surfaces_backend_detail(fake, monkeypatch):
    async def boom(order):
        raise TradecoreError(422, "insufficient cash")

    monkeypatch.setattr(fake, "submit", boom)
    with pytest.raises(RuntimeError, match="insufficient cash"):
        await _call(server.submit_order, symbol="ACME", side="BUY", quantity=1, type="LIMIT", price=1.0)
