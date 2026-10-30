import asyncio

import httpx
import pytest

from app.providers import GoCampingAdapter, ProviderFailure


def test_snapshot_reuses_connection_pool_and_closes_it(monkeypatch):
    clients = []

    async def get(client, url, params):
        if clients and client is not clients[0]:
            raise httpx.ConnectTimeout("new connection unavailable")
        clients.append(client)
        start = (params["pageNo"] - 1) * 100
        rows = [{"contentId": str(n)} for n in range(start, min(start + 100, 201))]
        return httpx.Response(200, json={"response": {"body": {
            "totalCount": 201, "items": {"item": rows},
        }}}, request=httpx.Request("GET", url))

    monkeypatch.setattr(httpx.AsyncClient, "get", get)
    sites, total, pages = asyncio.run(GoCampingAdapter("test-key").fetch_snapshot())
    assert len(sites) == total == 201 and pages == 3
    assert len(clients) == 3 and clients[0].is_closed


def test_snapshot_http_rejection_is_not_retried_and_pool_closes(monkeypatch):
    clients = []

    async def get(client, url, params):
        clients.append(client)
        if params["pageNo"] == 2:
            return httpx.Response(403, request=httpx.Request("GET", url))
        rows = [{"contentId": str(n)} for n in range(100)]
        return httpx.Response(200, json={"response": {"body": {
            "totalCount": 101, "items": {"item": rows},
        }}}, request=httpx.Request("GET", url))

    monkeypatch.setattr(httpx.AsyncClient, "get", get)
    with pytest.raises(ProviderFailure, match="HTTP 403"):
        asyncio.run(GoCampingAdapter("test-key").fetch_snapshot())
    assert len(clients) == 2 and all(client.is_closed for client in clients)
