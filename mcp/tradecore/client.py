"""REST + auth client for the tradecore backend. One responsibility: HTTP with a bearer token.

Auth mirrors scripts/token.sh / the pre-OIDC BFF: a Keycloak direct-access (password) grant
for a configured demo user, cached and refreshed near expiry. Dev/local only — a production
MCP would forward the end user's own token (see ADR-0018 for that journey).
"""

from __future__ import annotations

import os
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

import httpx


class TradecoreError(RuntimeError):
    """A backend call failed; carries the RFC 9457 problem detail when present."""

    def __init__(self, status: int, detail: str):
        super().__init__(f"backend {status}: {detail}")
        self.status = status
        self.detail = detail


@dataclass
class Settings:
    backend_url: str = field(default_factory=lambda: os.getenv("BACKEND_URL", "http://localhost:8080"))
    keycloak_url: str = field(default_factory=lambda: os.getenv("KEYCLOAK_URL", "http://localhost:8081"))
    realm: str = field(default_factory=lambda: os.getenv("KEYCLOAK_REALM", "tradecore"))
    client_id: str = field(default_factory=lambda: os.getenv("KEYCLOAK_CLIENT", "tradecore-api"))
    user: str = field(default_factory=lambda: os.getenv("MCP_USER", "trader1"))
    password: str = field(default_factory=lambda: os.getenv("MCP_PASSWORD", "demo"))


class TradecoreClient:
    def __init__(self, settings: Settings | None = None, http: httpx.AsyncClient | None = None, clock=time.time):
        self._s = settings or Settings()
        self._http = http or httpx.AsyncClient(timeout=10.0)
        self._clock = clock
        self._token: str | None = None
        self._expires_at: float = 0.0

    async def _access_token(self) -> str:
        if self._token and self._expires_at > self._clock() + 5:
            return self._token
        url = f"{self._s.keycloak_url}/realms/{self._s.realm}/protocol/openid-connect/token"
        resp = await self._http.post(
            url,
            data={
                "grant_type": "password",
                "client_id": self._s.client_id,
                "username": self._s.user,
                "password": self._s.password,
            },
        )
        if resp.status_code != 200:
            raise TradecoreError(resp.status_code, "Keycloak token request failed")
        body = resp.json()
        self._token = body["access_token"]
        self._expires_at = self._clock() + body.get("expires_in", 60)
        return self._token

    async def _call(self, method: str, path: str, body: Any | None = None) -> Any:
        headers = {"Authorization": f"Bearer {await self._access_token()}"}
        if method == "POST":
            headers["Idempotency-Key"] = str(uuid.uuid4())
        resp = await self._http.request(method, f"{self._s.backend_url}{path}", headers=headers, json=body)
        if resp.status_code >= 400:
            raise TradecoreError(resp.status_code, _detail(resp))
        text = resp.text
        return resp.json() if text else None

    # --- REST surface (mirrors the backend controllers) ---
    async def instruments(self) -> list[dict]:
        return await self._call("GET", "/api/v1/instruments")

    async def positions(self) -> list[dict]:
        return await self._call("GET", "/api/v1/positions")

    async def balances(self) -> dict:
        return await self._call("GET", "/api/v1/balances")

    async def orders(self, scope: str | None = None) -> list[dict]:
        return await self._call("GET", "/api/v1/orders" + ("?scope=all" if scope == "all" else ""))

    async def order(self, order_id: int) -> dict:
        return await self._call("GET", f"/api/v1/orders/{order_id}")

    async def submit(self, order: dict) -> dict:
        return await self._call("POST", "/api/v1/orders", order)

    async def cancel(self, order_id: int) -> dict:
        return await self._call("POST", f"/api/v1/orders/{order_id}/cancel")


def _detail(resp: httpx.Response) -> str:
    try:
        return resp.json().get("detail") or resp.json().get("title") or resp.text
    except Exception:
        return resp.text or f"HTTP {resp.status_code}"
