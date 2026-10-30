#!/usr/bin/env python3
"""Opt-in verification of real Turso, using one disposable relational scope.

Only additive Camp Room DDL and this random scope are written. Cleanup deletes
probe child rows in FK order; foreign keys are never disabled and unrelated
scope rows and the superseded prototype table are never changed.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import uuid

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/"apps/api"))
import httpx
from dotenv import load_dotenv
from fastapi.testclient import TestClient
from app.config import Settings
from app.main import create_app
from app.sql_store import Statement, SqlFailure
from app.turso_repository import TursoRepository, TursoSQL

SCOPED_TABLES=["weather_forecasts","weather_queries","availability_queries","order_fulfillment","order_receiving_conditions","order_deliveries","order_items","idempotency_keys","orders","cart_items","carts","vehicle_zones","vehicle_states","saved_scene_zones","saved_scenes","trips","profiles","campsite_facilities","campsite_rules","receiving_policies","campsite_availability_overviews","campsites","inventory","products","refresh_leases","refresh_runs","provider_runs","providers","scopes"]


class LostCommitResponse:
    """Inject loss after a real COMMIT response, preserving proxy/TLS settings."""
    def __init__(self, client):
        self.inner=client
        self.armed=False
        self.lost=0

    def post(self, url, *, json):
        response=self.inner.post(url,json=json)
        is_commit=any(r.get("stmt",{}).get("sql")=="COMMIT" for r in json.get("requests",[]))
        if self.armed and is_commit:
            response.raise_for_status()
            response.read()
            self.armed=False
            self.lost+=1
            response.close()
            raise httpx.ReadTimeout("Injected response loss after real remote COMMIT")
        return response

    def close(self):
        self.inner.close()


def run():
    parser=argparse.ArgumentParser()
    parser.add_argument("--run-remote",action="store_true")
    args=parser.parse_args()
    if not args.run_remote:parser.error("Explicit --run-remote is required")
    load_dotenv(ROOT/".env",override=False)
    url,token=os.getenv("TURSO_DATABASE_URL",""),os.getenv("TURSO_AUTH_TOKEN","")
    if not url or not token:raise RuntimeError("Turso credentials are not configured")
    scope="verification-"+uuid.uuid4().hex
    report={"status":"running","verifiedAt":datetime.now(timezone.utc).isoformat(),"backend":"turso","namespace":scope,"schemaVersion":1,"checks":[]}
    destination=ROOT/"artifacts/qa/turso-results.json"
    repositories=[]
    clients=[]
    schema=TursoSQL(url,token)
    previous_counts={}
    legacy_count=None

    def check(label,condition):
        if not condition:raise AssertionError(label)
        report["checks"].append({"name":label,"status":"passed"})
        print("PASS "+label,flush=True)

    def repository(profile="default",transport=None):
        repo=TursoRepository(url,token,scope,profile_id=profile,transport=transport)
        repositories.append(repo)
        repo.initialize()
        return repo

    def client(repo):
        app=create_app(Settings(mode="demo",db_path=Path("/tmp/camp-room-probe-unused.sqlite3"),admin_token="isolated-probe-admin"))
        app.state.service.repo.close()
        app.state.service.repo=repo
        value=TestClient(app)
        value.__enter__()
        clients.append(value)
        return value

    try:
        existing={row[0] for row in schema.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").rows}
        report["existingTables"]=sorted(existing)
        for table in SCOPED_TABLES:
            if "cr_"+table in existing:
                previous_counts[table]=schema.execute(f"SELECT COUNT(*) FROM cr_{table} WHERE scope_id<>?",(scope,)).rows[0][0]
        if "camp_room_state" in existing:
            legacy_count=schema.execute("SELECT COUNT(*) FROM camp_room_state").rows[0][0]
        check("Remote schema inspected before additive migrations",True)
        primary=repository()
        peer=repository()
        def increment(repo):
            repo.transact(lambda state:state["cart"].update({"camp-lantern":state["cart"].get("camp-lantern",0)+1}))
        with ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(increment,[primary,peer,primary,peer]))
        check("Four remote workers preserve all cart increments",primary.read()["cart"]=={"camp-lantern":4})
        def aborted(state):
            state["cart"]["sleeping-bag"]=9
            raise RuntimeError("Expected abort")
        try:primary.transact(aborted)
        except RuntimeError:pass
        check("Callback abort leaves committed remote rows unchanged",primary.read()["cart"]=={"camp-lantern":4})
        try:
            primary._write([Statement("UPDATE cr_inventory SET available=available-1 WHERE scope_id=? AND product_id='sleeping-bag'",(scope,)),Statement("INSERT INTO cr_cart_items(scope_id,profile_id,product_id,quantity) VALUES(?,?,?,1)",(scope,"default","missing-product"))])
            raise AssertionError("Foreign key failure was expected")
        except SqlFailure:pass
        check("Real foreign-key error rolls back an earlier stock write",primary.read()["inventory"]["sleeping-bag"]==20)
        primary.transact(lambda state:state["cart"].clear())
        a_repo=repository("last-stock-a")
        b_repo=repository("last-stock-b")
        primary.transact(lambda state:state["inventory"].update({"sleeping-bag":1}))
        a,b=client(a_repo),client(b_repo)
        for c in (a,b):check("Isolated profile cart can reserve a requested quantity",c.post("/api/v1/cart/items",json={"productId":"sleeping-bag","quantity":1}).status_code==200)
        def competing(pair):
            i,c=pair
            return c.post("/api/v1/orders",json={"destination":"home","recipientName":"검증용 가상 수령인","address":"검증용 가상 주소","idempotencyKey":f"last-stock-{i}"})
        with ThreadPoolExecutor(max_workers=2) as pool:competing_results=list(pool.map(competing,enumerate([a,b])))
        check("Two actual HTTP orders for the final stock produce one success and one conflict",sorted(r.status_code for r in competing_results)==[200,409])
        check("Remote inventory cannot oversell and losing cart is retained",primary.read()["inventory"]["sleeping-bag"]==0 and len(a_repo.read()["cart"])+len(b_repo.read()["cart"])==1)
        c,d=client(primary),client(peer)
        check("Order retry fixture is added through the HTTP API",c.post("/api/v1/cart/items",json={"productId":"coffee-kit","quantity":2}).status_code==200)
        request={"destination":"home","recipientName":"검증용 가상 수령인","address":"검증용 가상 주소","idempotencyKey":"same-key-concurrent"}
        with ThreadPoolExecutor(max_workers=2) as pool:responses=list(pool.map(lambda api:api.post("/api/v1/orders",json=request),[c,d]))
        check("Concurrent same-key HTTP order requests return the same persisted order",all(r.status_code==200 for r in responses) and responses[0].json()["id"]==responses[1].json()["id"])
        state=primary.read()
        check("Order, items, stock, idempotency and cart commit atomically",len(state["orders"])==1 and state["inventory"]["coffee-kit"]==10 and not state["cart"] and len(state["idempotency"])==1)
        check("Stored transaction remains an explicit unpaid demo",state["orders"][0]["mode"]=="demo" and state["orders"][0]["status"]=="demo_confirmed")
        lost_repo=repository("lost-response")
        fault=LostCommitResponse(lost_repo.sql.client)
        lost_repo.sql.client=fault
        lost_client=client(lost_repo)
        check("Response-loss fixture uses an isolated profile",lost_client.post("/api/v1/cart/items",json={"productId":"camp-lantern","quantity":1}).status_code==200)
        fault.armed=True
        lost_request=request|{"idempotencyKey":"commit-response-lost"}
        first=lost_client.post("/api/v1/orders",json=lost_request)
        replay=lost_client.post("/api/v1/orders",json=lost_request)
        check("Real committed response loss returns 503 and same-key HTTP replay recovers",first.status_code==503 and replay.status_code==200 and fault.lost==1)
        check("Response-loss replay creates one order and decrements stock once",len(lost_repo.read()["orders"])==1 and lost_repo.read()["inventory"]["camp-lantern"]==29)
        admin={"Authorization":"Bearer isolated-probe-admin"}
        updated=c.patch("/api/v1/admin/campsites/solbaram/receiving",headers=admin,json={"acceptsParcels":False,"location":"격리 검증 보관소","instructions":"검증 중"})
        check("Operator receiving policy persists across remote connections",updated.status_code==200 and not d.get("/api/v1/campsites/solbaram").json()["delivery"]["acceptsParcels"])
        order_id=responses[0].json()["id"]
        fulfillment=c.patch("/api/v1/admin/orders/"+order_id,headers=admin,json={"fulfillmentStatus":"packing","adminNote":"격리된 데모 검증"})
        check("Demo fulfillment persists without changing the order payment state",fulfillment.status_code==200 and peer.read()["orders"][0]["fulfillment"]["status"]=="packing" and peer.read()["orders"][0]["status"]=="demo_confirmed")
        refreshed=c.post("/api/v1/admin/providers/refresh",headers=admin,json={"force":True})
        check("Worker refresh publishes normalized durable weather rows and run history",refreshed.status_code==200 and refreshed.json()["weatherQueries"]==3 and primary.refresh_history()[0]["status"]=="succeeded" and schema.execute("SELECT COUNT(*) FROM cr_weather_queries WHERE scope_id=?",(scope,)).rows[0][0]==3)
        check("Provider refresh preserves operator receiving overrides",not d.get("/api/v1/campsites/solbaram").json()["delivery"]["acceptsParcels"])
        check("Remote normalized schema has no foreign-key violations",not schema.execute("PRAGMA foreign_key_check").rows)
        report["status"]="passed"
    except Exception as exc:
        report["status"]="failed"
        report["failureType"]=type(exc).__name__
        report["failureCode"]=getattr(exc,"code",getattr(exc,"reason","unknown"))
        raise
    finally:
        # Finish clients before cleanup so their outstanding sessions cannot publish.
        for value in clients:
            try:value.__exit__(None,None,None)
            except Exception:pass
        try:
            if repositories:
                current_tables={row[0] for row in schema.execute("SELECT name FROM sqlite_master WHERE type='table'").rows}
                session=schema.session()
                try:
                    session.begin(True)
                    session.run([Statement(f"DELETE FROM cr_{table} WHERE scope_id=?",(scope,)) for table in SCOPED_TABLES if "cr_"+table in current_tables])
                    session.commit()
                finally:session.close()
                report["probeCleaned"]="cr_scopes" not in current_tables or schema.execute("SELECT COUNT(*) FROM cr_scopes WHERE scope_id=?",(scope,)).rows[0][0]==0
                report["unrelatedScopesPreserved"]=all(schema.execute(f"SELECT COUNT(*) FROM cr_{table} WHERE scope_id<>?",(scope,)).rows[0][0]==count for table,count in previous_counts.items())
                report["legacyPrototypeUntouched"]=legacy_count is None or schema.execute("SELECT COUNT(*) FROM camp_room_state").rows[0][0]==legacy_count
            else:report["probeCleaned"]=True
            if not report.get("probeCleaned") or report.get("unrelatedScopesPreserved") is False or report.get("legacyPrototypeUntouched") is False:report["status"]="failed"
        except Exception as exc:
            report["probeCleaned"]=False
            report["cleanupFailureType"]=type(exc).__name__
            report["status"]="failed"
        for repo in repositories:
            try:repo.close()
            except Exception:pass
        schema.close()
        destination.parent.mkdir(parents=True,exist_ok=True)
        destination.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    if report["status"]!="passed" or not report["probeCleaned"]:raise RuntimeError("Remote verification/cleanup failed; inspect sanitized report")
    print("PASS Isolated probe rows removed with FKs enabled; unrelated data preserved",flush=True)


if __name__=="__main__":
    try:run()
    except Exception as error:
        print("TURSO VERIFICATION FAILED: "+type(error).__name__,file=sys.stderr)
        sys.exit(1)
