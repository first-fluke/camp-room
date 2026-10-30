import json
import sqlite3
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.repository import Repository
from app.sql_store import Statement, SqlFailure
from app.turso_repository import TursoRepository, StorageUnavailable


class SQLiteHrana:
    """Stateful Hrana protocol double, using actual SQLite locks/FKs/rollback."""
    def __init__(self, path: Path):
        self.path = path
        self.streams = {}
        self.lock = threading.Lock()
        self.fail_next_commit = False
        self.fail_statement_prefix = None
        self.commit_calls = 0

    @staticmethod
    def encode(value):
        if value is None:return {"type":"null"}
        if isinstance(value,int):return {"type":"integer","value":str(value)}
        if isinstance(value,float):return {"type":"float","value":value}
        return {"type":"text","value":value}

    def __call__(self, request):
        body = json.loads(request.content)
        baton=body.get("baton")
        with self.lock:
            if baton:
                db=self.streams.pop(baton)
            else:
                db=sqlite3.connect(self.path,isolation_level=None,timeout=10,check_same_thread=False)
        closed=False
        lose_response=False
        results=[]
        for operation in body["requests"]:
            if operation["type"]=="close":
                db.close();closed=True
                results.append({"type":"ok","response":{"type":"close"}})
                continue
            stmt=operation["stmt"]
            args=[None if a["type"]=="null" else int(a["value"]) if a["type"]=="integer" else a["value"] for a in stmt.get("args",[])]
            try:
                if self.fail_statement_prefix and stmt["sql"].startswith(self.fail_statement_prefix):
                    raise sqlite3.IntegrityError("Injected SQL constraint failure")
                cursor=db.execute(stmt["sql"],args)
                names=[c[0] for c in cursor.description or []]
                rows=cursor.fetchall()
                results.append({"type":"ok","response":{"type":"execute","result":{"cols":[{"name":name} for name in names],"rows":[[self.encode(value) for value in row] for row in rows],"affected_row_count":max(cursor.rowcount,0)}}})
                if stmt["sql"]=="COMMIT":
                    with self.lock:
                        self.commit_calls+=1
                        if self.fail_next_commit:
                            self.fail_next_commit=False
                            lose_response=True
            except sqlite3.DatabaseError as exc:
                # Hrana continues subsequent pipeline statements after an error.
                results.append({"type":"error","error":{"code":getattr(exc,"sqlite_errorname","SQLITE_CONSTRAINT")}})
        new_baton=None if closed else uuid.uuid4().hex
        if lose_response:
            db.close()
            raise httpx.ReadTimeout("simulated committed response loss",request=request)
        if not closed:
            with self.lock:self.streams[new_baton]=db
        return httpx.Response(200,json={"baton":new_baton,"base_url":None,"results":results})


def remote(tmp_path, namespace="test-scope", profile_id="default", server=None):
    server=server or SQLiteHrana(tmp_path/"hrana.sqlite3")
    return TursoRepository("libsql://unit.invalid","unit-token",namespace,profile_id=profile_id,transport=httpx.MockTransport(server)),server


def remote_client(tmp_path, repository):
    app=create_app(Settings(mode="demo",db_path=tmp_path/(repository.profile_id+"-unused.sqlite3")))
    app.state.service.repo.close()
    app.state.service.repo=repository
    return TestClient(app)


def test_turso_concurrent_workers_do_not_lose_cart_updates(tmp_path):
    first,server=remote(tmp_path)
    second,_=remote(tmp_path,server=server)
    first.initialize();second.initialize()
    def increment(repo):
        repo.transact(lambda state:state["cart"].update({"camp-lantern":state["cart"].get("camp-lantern",0)+1}))
    with ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(increment,[first,second]*4))
    assert first.read()["cart"]["camp-lantern"]==8
    first.close();second.close()


def test_turso_callback_abort_and_mid_write_failure_roll_back(tmp_path):
    repo,server=remote(tmp_path)
    def aborted(state):
        state["cart"]["sleeping-bag"]=3
        raise RuntimeError("abort")
    with pytest.raises(RuntimeError):repo.transact(aborted)
    assert repo.read()["cart"]=={}
    with remote_client(tmp_path,repo) as client:
        client.post("/api/v1/cart/items",json={"productId":"sleeping-bag","quantity":1})
        server.fail_statement_prefix="INSERT INTO cr_orders"
        response=client.post("/api/v1/orders",json={"destination":"home","recipientName":"검증","address":"가상 주소","idempotencyKey":"rollback-order-01"})
        assert response.status_code==503
        state=repo.read()
        assert state["cart"]=={"sleeping-bag":1}
        assert state["inventory"]["sleeping-bag"]==20
        assert state["orders"]==[] and state["idempotency"]=={}


def test_last_stock_competing_http_orders_cannot_both_succeed(tmp_path):
    first,server=remote(tmp_path,profile_id="first")
    second,_=remote(tmp_path,profile_id="second",server=server)
    first.initialize();second.initialize()
    first.transact(lambda state:state["inventory"].update({"sleeping-bag":1}))
    with remote_client(tmp_path,first) as a,remote_client(tmp_path,second) as b:
        for client in (a,b):assert client.post("/api/v1/cart/items",json={"productId":"sleeping-bag","quantity":1}).status_code==200
        def order(pair):
            index,client=pair
            return client.post("/api/v1/orders",json={"destination":"home","recipientName":"검증","address":"가상 주소","idempotencyKey":f"last-stock-order-{index}"})
        with ThreadPoolExecutor(max_workers=2) as pool:responses=list(pool.map(order,enumerate([a,b])))
        assert sorted(r.status_code for r in responses)==[200,409]
        assert first.read()["inventory"]["sleeping-bag"]==0
        assert len(first.read(all_profiles=True)["orders"])==1
        assert len(first.read()["cart"])+len(second.read()["cart"])==1


def test_ambiguous_commit_recovered_by_same_key_http_replay(tmp_path):
    repo,server=remote(tmp_path)
    with remote_client(tmp_path,repo) as client:
        assert client.post("/api/v1/cart/items",json={"productId":"coffee-kit","quantity":2}).status_code==200
        commits_before=server.commit_calls
        server.fail_next_commit=True
        request={"destination":"home","recipientName":"검증","address":"가상 주소","idempotencyKey":"lost-commit-response"}
        first=client.post("/api/v1/orders",json=request)
        assert first.status_code==503
        assert server.commit_calls==commits_before+1
        second=client.post("/api/v1/orders",json=request)
        assert second.status_code==200
        assert server.commit_calls==commits_before+1  # No blind retry or second order write.
        state=repo.read()
        assert len(state["orders"])==1 and state["inventory"]["coffee-kit"]==10 and state["cart"]=={}
        assert state["orders"][0]["id"]==second.json()["id"]
        assert client.post("/api/v1/orders",json=request|{"recipientName":"다른 이름"}).status_code==409


def test_relational_types_dates_and_cross_scope_foreign_keys(tmp_path):
    repo=Repository(tmp_path/"constraints.sqlite3",namespace="first")
    other=Repository(tmp_path/"constraints.sqlite3",namespace="second",profile_id="foreign")
    repo.initialize();other.initialize()
    statements=[
        Statement("UPDATE cr_products SET price=1.5 WHERE scope_id='first' AND product_id='coffee-kit'"),
        Statement("UPDATE cr_inventory SET available=-1 WHERE scope_id='first' AND product_id='coffee-kit'"),
        Statement("UPDATE cr_trips SET check_in='2026-02-30' WHERE scope_id='first'"),
        Statement("INSERT INTO cr_cart_items(scope_id,profile_id,product_id,quantity) VALUES('first','foreign','coffee-kit',1)"),
    ]
    for statement in statements:
        with pytest.raises(SqlFailure):repo._write([statement])
    with sqlite3.connect(repo.path) as db:
        names={row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert "state" not in names and "camp_room_state" not in names
        assert db.execute("PRAGMA foreign_key_check").fetchall()==[]
    assert repo.read()["inventory"]["coffee-kit"]==12
    assert repo.read()["cart"]=={} and other.read()["cart"]=={}


def test_database_selection_keeps_explicit_local_override(monkeypatch, tmp_path):
    monkeypatch.setenv("TURSO_DATABASE_URL", "libsql://remote.invalid")
    monkeypatch.setenv("TURSO_AUTH_TOKEN", "unit-token")
    monkeypatch.delenv("CAMP_DB_PATH", raising=False)
    assert Settings.from_env().turso_url
    monkeypatch.setenv("CAMP_DB_PATH", str(tmp_path / "isolated.sqlite3"))
    settings = Settings.from_env()
    with TestClient(create_app(settings)) as client:
        assert client.get("/health").json()["database"] == "sqlite"
        assert client.get("/api/v1/bootstrap").status_code == 200


def admin_client(tmp_path):
    settings = Settings(mode="demo", db_path=tmp_path / "admin.sqlite3", admin_token="unit-admin-secret")
    return TestClient(create_app(settings))


ADMIN = {"Authorization": "Bearer unit-admin-secret"}


def test_admin_auth_never_exposes_settings(tmp_path):
    with admin_client(tmp_path) as client:
        assert client.get("/api/v1/admin/overview").status_code == 401
        assert client.get("/api/v1/admin/overview", headers={"Authorization": "Bearer wrong"}).status_code == 401
        response = client.get("/api/v1/admin/overview", headers=ADMIN)
        assert response.status_code == 200
        assert response.json()["database"]["backend"] == "sqlite"
        assert "unit-admin-secret" not in response.text
        assert "TURSO_AUTH_TOKEN" not in response.text
    with TestClient(create_app(Settings(mode="demo", db_path=tmp_path / "disabled.sqlite3"))) as client:
        assert client.get("/api/v1/admin/overview", headers=ADMIN).status_code == 503


def test_admin_receiving_override_controls_quote_and_order(tmp_path):
    with admin_client(tmp_path) as client:
        client.get("/api/v1/bootstrap")
        checkin = date.today() + timedelta(days=2)
        client.post("/api/v1/cart/items", json={"productId": "sleeping-bag", "quantity": 1})
        request = {"destination": "campsite", "campsiteId": "solbaram", "checkIn": checkin.isoformat(), "checkOut": (checkin + timedelta(days=1)).isoformat(), "recipientName": "가상 수령인", "siteReference": "A-12"}
        response = client.patch("/api/v1/admin/campsites/solbaram/receiving", headers=ADMIN, json={"acceptsParcels": False, "location": "관리동", "instructions": "택배 접수 중단"})
        assert response.status_code == 200
        assert not client.get("/api/v1/campsites/solbaram").json()["delivery"]["acceptsParcels"]
        assert not client.post("/api/v1/checkout/quote", json=request).json()["eligible"]
        assert client.post("/api/v1/orders", json=request | {"idempotencyKey": "admin-blocked-order"}).status_code == 409
        assert client.get("/api/v1/cart").json()["items"]
        assert client.patch("/api/v1/admin/campsites/solbaram/receiving", headers=ADMIN, json={"acceptsParcels": True, "location": "", "instructions": ""}).status_code == 422
        client.patch("/api/v1/admin/campsites/solbaram/receiving", headers=ADMIN, json={"acceptsParcels": True, "location": "새 택배 보관소", "instructions": "오후 수령"})
        quote = client.post("/api/v1/checkout/quote", json=request).json()
        assert quote["eligible"] and "새 택배 보관소" in quote["address"]
        order = client.post("/api/v1/orders", json=request | {"idempotencyKey": "admin-reopened-order"}).json()
        status = client.patch("/api/v1/admin/orders/" + order["id"], headers=ADMIN, json={"fulfillmentStatus": "packing", "adminNote": "데모 포장 확인"})
        assert status.status_code == 200
        assert status.json()["status"] == "demo_confirmed"
        assert status.json()["fulfillment"]["mode"] == "demo"
        assert client.get("/api/v1/orders").json()["items"][0]["fulfillment"]["status"] == "packing"
        assert client.patch("/api/v1/admin/orders/missing", headers=ADMIN, json={"fulfillmentStatus": "ready", "adminNote": ""}).status_code == 404


def test_provider_refresh_is_durable_bounded_and_preserves_admin_policy(tmp_path):
    with admin_client(tmp_path) as client:
        assert client.patch("/api/v1/admin/campsites/solbaram/receiving",headers=ADMIN,json={"acceptsParcels":False,"location":"관리자 지정","instructions":"수령 중단"}).status_code==200
        refreshed=client.post("/api/v1/admin/providers/refresh",headers=ADMIN,json={"force":True})
        assert refreshed.status_code==200 and refreshed.json()["status"]=="refreshed"
        assert refreshed.json()["weatherQueries"]==3
        repo=client.app.state.service.repo
        assert repo.sql.execute("SELECT COUNT(*) FROM cr_weather_queries WHERE scope_id=?",(repo.namespace,)).rows[0][0]==3
        assert repo.refresh_history()[0]["status"]=="succeeded"
        assert not client.get("/api/v1/campsites/solbaram").json()["delivery"]["acceptsParcels"]
        # A new process can use the stored cache even if the network adapter fails.
        with admin_client(tmp_path) as restarted:
            restarted.app.state.service.providers.catalog=AsyncMock(side_effect=AssertionError("network called despite warm cache"))
            restarted.app.state.service.providers.weather=AsyncMock(side_effect=AssertionError("network called despite warm cache"))
            assert restarted.get("/api/v1/bootstrap").status_code==200
            assert restarted.post("/api/v1/admin/providers/refresh",headers=ADMIN,json={"force":False}).json()["status"]=="cached"
        owner=repo.begin_refresh()
        assert owner and repo.begin_refresh() is None
        assert client.post("/api/v1/admin/providers/refresh",headers=ADMIN,json={"force":True}).json()["status"]=="busy"
        repo.finish_refresh(owner,"failed","test_failure")
        history=repo.refresh_history()
        assert history[0]["status"]=="failed" and any(run["status"]=="succeeded" for run in history)
        assert repo.sql.execute("SELECT COUNT(*) FROM cr_refresh_leases WHERE scope_id=?",(repo.namespace,)).rows[0][0]==0


def test_availability_query_context_and_weather_variant_replacement(tmp_path):
    with admin_client(tmp_path) as client:
        service=client.app.state.service
        trip=client.get("/api/v1/trip").json()
        for guests in (2,7):
            response=client.post("/api/v1/availability/query",json={"campsiteId":"solbaram","checkIn":trip["checkIn"],"checkOut":trip["checkOut"],"guests":guests})
            assert response.status_code==200
        rows=service.repo.sql.execute("SELECT guests,status FROM cr_availability_queries WHERE scope_id=? ORDER BY guests",(service.repo.namespace,)).rows
        assert rows==((2,"available"),(7,"unavailable"))
        weather=client.get("/api/v1/weather").json()
        variant="test-alternate-config"
        service.repo.store_weather("solbaram",trip["checkIn"],trip["checkOut"],weather,service.providers.weather_status,variant)
        assert service.repo.cached_weather("solbaram",trip["checkIn"],trip["checkOut"],service.cache_variant) is None
        assert service.repo.cached_weather("solbaram",trip["checkIn"],trip["checkOut"],variant)[0]==weather
        assert service.repo.sql.execute("SELECT COUNT(*) FROM cr_weather_queries WHERE scope_id=?",(service.repo.namespace,)).rows[0][0]==1


def test_failed_refresh_keeps_last_success_and_fences_expired_owner(tmp_path):
    with admin_client(tmp_path) as client:
        service=client.app.state.service
        assert client.post("/api/v1/admin/providers/refresh",headers=ADMIN,json={"force":True}).status_code==200
        repo=service.repo
        before=repo.sql.execute("SELECT query_key,updated_at,summary FROM cr_weather_queries WHERE scope_id=? ORDER BY query_key",(repo.namespace,)).rows
        site_count=len(repo.read()["catalog"])
        async def failed_catalog():
            service.providers.catalog_status.update(status="unavailable",message="검증용 공급자 오류")
            return []
        async def failed_weather(site,checkin,checkout):
            service.providers.weather_status.update(status="unavailable",message="검증용 날씨 오류")
            return {"source":"조회 불가","updatedAt":"2030-01-01T00:00:00+00:00","summary":"예보 없음","overnightLow":None,"rainProbability":None,"forecast":[],"demo":True,"status":"unavailable"}
        service.providers.catalog=failed_catalog
        service.providers.weather=failed_weather
        result=client.post("/api/v1/admin/providers/refresh",headers=ADMIN,json={"force":True})
        assert result.status_code==200
        assert all(status["status"]=="unavailable" for status in result.json()["providers"])
        assert repo.sql.execute("SELECT query_key,updated_at,summary FROM cr_weather_queries WHERE scope_id=? ORDER BY query_key",(repo.namespace,)).rows==before
        assert len(repo.read()["catalog"])==site_count
        history=repo.refresh_history()
        assert history[0]["status"]=="failed" and history[1]["status"]=="succeeded"
        warning=client.get("/api/v1/weather").json()
        assert warning["status"]=="available" and "갱신 시각" in warning["message"]
        old=repo.begin_refresh()
        repo._write([Statement("UPDATE cr_refresh_leases SET expires_at='2000-01-01T00:00:00+00:00' WHERE scope_id=?",(repo.namespace,))])
        new=repo.begin_refresh()
        assert new and new!=old
        with pytest.raises(Exception) as exc:
            repo.store_catalog([],service.providers.catalog_status,service.cache_variant,lease=("provider-cache",old))
        assert getattr(exc.value,"status",None)==409
        assert len(repo.read()["catalog"])==site_count
        repo.finish_refresh(old,"failed","lease_expired")
        assert repo.sql.execute("SELECT lease_owner FROM cr_refresh_leases WHERE scope_id=?",(repo.namespace,)).rows==((new,),)
        repo.finish_refresh(new,"succeeded")
