import httpx
import pytest

from tradecore.client import Settings, TradecoreClient, TradecoreError

TOKEN_PATH = "/realms/tradecore/protocol/openid-connect/token"


def _transport(handler):
    return httpx.MockTransport(handler)


def _settings():
    return Settings(
        backend_url="http://backend.test",
        keycloak_url="http://kc.test",
        realm="tradecore",
        client_id="tradecore-api",
        user="trader1",
        password="demo",
    )


async def test_acquires_and_reuses_token():
    calls = {"token": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == TOKEN_PATH:
            calls["token"] += 1
            return httpx.Response(200, json={"access_token": "at-1", "expires_in": 300})
        assert request.headers["Authorization"] == "Bearer at-1"
        return httpx.Response(200, json=[])

    http = httpx.AsyncClient(transport=_transport(handler))
    client = TradecoreClient(_settings(), http=http, clock=lambda: 1000.0)

    await client.instruments()
    await client.positions()
    assert calls["token"] == 1  # token cached across calls


async def test_refreshes_expired_token():
    calls = {"token": 0}
    now = {"t": 1000.0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == TOKEN_PATH:
            calls["token"] += 1
            return httpx.Response(200, json={"access_token": "at", "expires_in": 100})
        return httpx.Response(200, json=[])

    http = httpx.AsyncClient(transport=_transport(handler))
    client = TradecoreClient(_settings(), http=http, clock=lambda: now["t"])
    await client.instruments()
    now["t"] += 200  # past expiry
    await client.instruments()
    assert calls["token"] == 2


async def test_submit_sends_bearer_idempotency_and_body():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == TOKEN_PATH:
            return httpx.Response(200, json={"access_token": "at", "expires_in": 300})
        seen["method"] = request.method
        seen["path"] = request.url.path
        seen["idem"] = request.headers.get("Idempotency-Key")
        seen["auth"] = request.headers.get("Authorization")
        import json

        seen["body"] = json.loads(request.content)
        return httpx.Response(201, json={"id": 7})

    http = httpx.AsyncClient(transport=_transport(handler))
    client = TradecoreClient(_settings(), http=http)
    out = await client.submit({"symbol": "ACME", "side": "BUY", "quantity": 5, "type": "LIMIT", "price": 10000})

    assert out == {"id": 7}
    assert seen["method"] == "POST"
    assert seen["path"] == "/api/v1/orders"
    assert seen["auth"] == "Bearer at"
    assert seen["idem"]  # a fresh idempotency key
    assert seen["body"]["price"] == 10000


async def test_error_carries_problem_detail():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == TOKEN_PATH:
            return httpx.Response(200, json={"access_token": "at", "expires_in": 300})
        return httpx.Response(409, json={"title": "Order not cancellable", "detail": "order 3 is REJECTED"})

    http = httpx.AsyncClient(transport=_transport(handler))
    client = TradecoreClient(_settings(), http=http)
    with pytest.raises(TradecoreError) as ex:
        await client.cancel(3)
    assert ex.value.status == 409
    assert ex.value.detail == "order 3 is REJECTED"


async def test_scope_all_query():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == TOKEN_PATH:
            return httpx.Response(200, json={"access_token": "at", "expires_in": 300})
        seen["url"] = str(request.url)
        return httpx.Response(200, json=[])

    http = httpx.AsyncClient(transport=_transport(handler))
    client = TradecoreClient(_settings(), http=http)
    await client.orders(scope="all")
    assert seen["url"].endswith("/api/v1/orders?scope=all")
