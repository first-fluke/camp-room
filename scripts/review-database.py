#!/usr/bin/env python3
"""Independent OMA DB review checks against the actual committed DDL.

This runs only in an in-memory SQLite database. It never reads credentials or
connects to the user's Turso database. Normal-form reasoning is recorded in
docs/database/SCHEMA_REVIEW.md; SQL checks alone cannot prove 3NF.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "apps/api/app/migrations/001_normalized.sql"
OUTPUT = ROOT / "artifacts/qa/db-review.json"
DB = sqlite3.connect(":memory:", isolation_level=None)
DB.row_factory = sqlite3.Row
CHECKS: list[dict[str, str]] = []
NOW = "2026-10-25T06:00:00Z"


def passed(name: str, detail: str = "") -> None:
    CHECKS.append({"name": name, "status": "passed", "detail": detail})
    print("PASS", name)


def require(name: str, condition: bool, detail: str = "") -> None:
    if not condition:
        raise AssertionError(f"{name}: {detail}")
    passed(name, detail)


def insert(table: str, **values):
    # Identifiers are fixed by this review script; all values are parameterized.
    columns = ",".join(values)
    placeholders = ",".join("?" for _ in values)
    return DB.execute(f"INSERT INTO {table} ({columns}) VALUES ({placeholders})", tuple(values.values()))


def rejects(name: str, operation) -> None:
    DB.execute("SAVEPOINT rejection_check")
    rejected = False
    try:
        operation()
    except sqlite3.IntegrityError:
        rejected = True
    finally:
        DB.execute("ROLLBACK TO rejection_check")
        DB.execute("RELEASE rejection_check")
    require(name, rejected, "Database constraint must reject the invalid write")


def site(scope: str, name: str):
    return dict(scope_id=scope, campsite_id=name, provider_id="catalog", name="Review campsite", region="Gangwon", address="Fixture address", latitude=37.7, longitude=128.0, nightly_price=50000, currency="KRW", car_camping_allowed=1, ev_charging_allowed=0, description="Review fixture", image_url="", check_in_time="14:00", check_out_time="11:00", source_updated_at=NOW, source_mode="demo", active=1)


def trip(**overrides):
    data = dict(scope_id="review-a", profile_id="p1", campsite_id="a-site", check_in="2026-10-31", check_out="2026-11-01", guests=2, navigation_started=0, updated_at=NOW)
    data.update(overrides)
    return data


def order(order_id: str, profile_id: str = "p1"):
    return dict(scope_id="review-a", order_id=order_id, profile_id=profile_id, status="demo_confirmed", mode="demo", created_at=NOW, shipping_charge=3000, currency="KRW")


def delivery(order_id: str, **overrides):
    data = dict(scope_id="review-a", order_id=order_id, destination="campsite", eta="2026-10-31", recipient_name="Review recipient", address_snapshot="Review receiving office", site_reference="A1", campsite_id="a-site", check_in="2026-10-31", check_out="2026-11-01")
    data.update(overrides)
    return data


def run():
    require("Python 3.14 runtime", sys.version_info[:2] == (3, 14), sys.version.split()[0])
    sql = SCHEMA.read_text()
    DB.executescript(sql)
    DB.executescript(sql)
    require("Additive DDL is repeatable", DB.execute("PRAGMA foreign_keys").fetchone()[0] == 1)
    tables = {r[0] for r in DB.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    require("Canonical storage is relational", "camp_room_state" not in tables and len(tables) >= 30, f"{len(tables)} normalized tables")
    require("Every canonical table enforces strict SQL types", all(r[5] == 1 for r in DB.execute("PRAGMA table_list") if r[1] in tables))
    columns = {t: [r[1] for r in DB.execute(f"PRAGMA table_info({t})")] for t in tables}
    require("No canonical JSON or generic EAV columns", not any(c in {"payload", "state_json", "data_json", "attribute_name", "attribute_value"} or c.endswith("_json") for cs in columns.values() for c in cs))
    for scope in ("review-a", "review-b"):
        insert("cr_scopes", scope_id=scope, created_at=NOW)
        for profile in ("p1", "p2"):
            insert("cr_profiles", scope_id=scope, profile_id=profile)
        for provider, kind in (("catalog", "catalog"), ("booking", "booking"), ("weather", "weather")):
            insert("cr_providers", scope_id=scope, provider_id=provider, name=provider, kind=kind)
    insert("cr_campsites", **site("review-a", "a-site"))
    insert("cr_campsites", **site("review-b", "b-site"))
    insert("cr_facilities", code="shower")
    insert("cr_campsite_facilities", scope_id="review-a", campsite_id="a-site", facility_code="shower")
    rejects("Facility membership is unique", lambda: insert("cr_campsite_facilities", scope_id="review-a", campsite_id="a-site", facility_code="shower"))
    rejects("Facility membership requires a valid facility", lambda: insert("cr_campsite_facilities", scope_id="review-a", campsite_id="a-site", facility_code="missing"))
    rejects("Trips cannot reference another scope's campsite", lambda: insert("cr_trips", **trip(campsite_id="b-site")))
    rejects("Trips reject impossible calendar dates", lambda: insert("cr_trips", **trip(check_in="2026-02-30", check_out="2026-03-02")))
    rejects("Trips reject non-date strings", lambda: insert("cr_trips", **trip(check_in="not-a-date", check_out="zzzzzzzzzz")))
    rejects("Trips reject zero nights", lambda: insert("cr_trips", **trip(check_out="2026-10-31")))
    rejects("Trips reject stays beyond 30 nights", lambda: insert("cr_trips", **trip(check_out="2026-12-02")))
    insert("cr_trips", **trip())

    availability = dict(scope_id="review-a", campsite_id="a-site", check_in="2026-10-31", check_out="2026-11-01", guests=2, slot_type="camping", provider_id="booking", status="unknown", remaining=None, coverage="query-specific", checked_at=NOW, expires_at="2026-10-25T06:05:00Z", demo=1)
    insert("cr_availability_queries", **availability)
    insert("cr_availability_queries", **(availability | {"guests": 3}))
    insert("cr_availability_queries", **(availability | {"check_in": "2026-11-02", "check_out": "2026-11-03"}))
    require("Availability separates dates and party size", DB.execute("SELECT COUNT(*) FROM cr_availability_queries").fetchone()[0] == 3)
    rejects("Availability deduplicates the complete query key", lambda: insert("cr_availability_queries", **availability))
    require("Unknown inventory remains NULL", DB.execute("SELECT remaining FROM cr_availability_queries LIMIT 1").fetchone()[0] is None)

    product = dict(scope_id="review-a", product_id="lantern", name="Original name", price=39000, currency="KRW", category="light", recommendation_reason="Evening use", image_url="")
    insert("cr_products", **product)
    rejects("Money cannot be negative", lambda: insert("cr_products", **(product | {"product_id": "invalid", "price": -1})))
    rejects("KRW money rejects fractional values", lambda: insert("cr_products", **(product | {"product_id": "fractional", "price": 39.5})))
    insert("cr_inventory", scope_id="review-a", product_id="lantern", available=1)
    rejects("Inventory cannot be negative", lambda: DB.execute("UPDATE cr_inventory SET available=-1"))
    rejects("Inventory rejects fractional quantities", lambda: DB.execute("UPDATE cr_inventory SET available=1.5"))
    for profile in ("p1", "p2"):
        insert("cr_carts", scope_id="review-a", profile_id=profile)
    rejects("Cart quantities must be positive", lambda: insert("cr_cart_items", scope_id="review-a", profile_id="p1", product_id="lantern", quantity=0))
    rejects("Cart items require a real product", lambda: insert("cr_cart_items", scope_id="review-a", profile_id="p1", product_id="missing", quantity=1))
    insert("cr_cart_items", scope_id="review-a", profile_id="p1", product_id="lantern", quantity=1)

    DB.execute("BEGIN IMMEDIATE")
    try:
        DB.execute("UPDATE cr_inventory SET available=available-1 WHERE scope_id=? AND product_id=? AND available>=1", ("review-a", "lantern"))
        insert("cr_orders", **order("rollback-order"))
        insert("cr_order_items", scope_id="review-a", order_id="rollback-order", line_number=0, product_id="missing", product_name_snapshot="Invalid", unit_price=39000, quantity=1)
        raise AssertionError("Expected foreign key violation")
    except sqlite3.IntegrityError:
        DB.execute("ROLLBACK")
    require("Failed checkout rolls inventory and order back together", DB.execute("SELECT available FROM cr_inventory").fetchone()[0] == 1 and DB.execute("SELECT COUNT(*) FROM cr_orders").fetchone()[0] == 0)
    require("Insufficient inventory changes no row", DB.execute("UPDATE cr_inventory SET available=available-2 WHERE scope_id=? AND product_id=? AND available>=2", ("review-a", "lantern")).rowcount == 0)

    insert("cr_orders", **order("historical-order"))
    insert("cr_order_items", scope_id="review-a", order_id="historical-order", line_number=0, product_id="lantern", product_name_snapshot="Original name", unit_price=39000, quantity=1)
    rejects("Delivery rejects checkout-day arrival", lambda: insert("cr_order_deliveries", **delivery("historical-order", eta="2026-11-01")))
    rejects("Campsite delivery requires a campsite", lambda: insert("cr_order_deliveries", **delivery("historical-order", campsite_id=None)))
    insert("cr_order_deliveries", **delivery("historical-order"))
    DB.execute("UPDATE cr_products SET name='Renamed product',price=41000 WHERE scope_id='review-a'")
    row = DB.execute("SELECT product_name_snapshot,unit_price FROM cr_order_items").fetchone()
    require("Order facts survive later catalog changes", tuple(row) == ("Original name", 39000))
    require("Order totals are derived", "subtotal" not in columns["cr_orders"] and "total" not in columns["cr_orders"])
    insert("cr_idempotency_keys", scope_id="review-a", profile_id="p1", request_key="retry-key", fingerprint="body-hash", order_id="historical-order")
    rejects("An idempotency key cannot be reused", lambda: insert("cr_idempotency_keys", scope_id="review-a", profile_id="p1", request_key="retry-key", fingerprint="body-hash", order_id="historical-order"))
    rejects("An idempotency record cannot claim another profile's order", lambda: insert("cr_idempotency_keys", scope_id="review-a", profile_id="p2", request_key="cross-profile", fingerprint="body-hash", order_id="historical-order"))
    rejects("Products referenced by historical orders cannot disappear", lambda: DB.execute("DELETE FROM cr_products WHERE scope_id='review-a'"))

    require("Weather labels are joined rather than duplicated", "source_name" not in columns["cr_weather_queries"])
    weather = dict(scope_id="review-a", query_key="weather-1", campsite_id="a-site", check_in="2026-10-31", check_out="2026-11-01", provider_id="weather", updated_at=NOW, expires_at="2026-10-25T06:05:00Z", variant="demo", summary="Cool night", overnight_low=9, rain_probability=20, demo=1, status="available")
    insert("cr_weather_queries", **weather)
    rejects("Weather query requires a site", lambda: insert("cr_weather_queries", **(weather | {"query_key": "no-site", "campsite_id": None})))
    rejects("Weather query is unique for site and stay", lambda: insert("cr_weather_queries", **(weather | {"query_key": "duplicate"})))
    insert("cr_refresh_runs", scope_id="review-a", run_id="run-success", job_key="catalog", provider_id="catalog", started_at=NOW, finished_at=NOW, status="succeeded", attempts=1, error_code=None)
    insert("cr_refresh_runs", scope_id="review-a", run_id="run-failure", job_key="catalog", provider_id="catalog", started_at=NOW, finished_at=NOW, status="failed", attempts=3, error_code="provider_unavailable")
    require("A failed refresh does not erase the last successful run", DB.execute("SELECT COUNT(*) FROM cr_refresh_runs WHERE status='succeeded'").fetchone()[0] == 1)
    rejects("Finished jobs require a finish timestamp", lambda: insert("cr_refresh_runs", scope_id="review-a", run_id="bad-run", job_key="catalog", provider_id="catalog", started_at=NOW, finished_at=None, status="failed", attempts=1, error_code="failed"))
    require("All foreign keys remain valid", DB.execute("PRAGMA foreign_key_check").fetchall() == [])
    require("Database integrity check passes", DB.execute("PRAGMA integrity_check").fetchone()[0] == "ok")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    report = {"status": "passed", "role": "oma-db independent reviewer", "python": sys.version.split()[0], "sqlite": sqlite3.sqlite_version, "schemaSha256": hashlib.sha256(sql.encode()).hexdigest(), "tableCount": len(tables), "checks": CHECKS, "normalFormReview": "docs/database/SCHEMA_REVIEW.md", "scope": "In-memory schema and declarative integrity; application transaction concurrency and remote Turso behavior are verified separately."}
    OUTPUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS {len(CHECKS)} independent schema checks; {len(tables)} tables")


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(json.dumps({"status": "failed", "error": type(error).__name__, "checks": CHECKS}, indent=2) + "\n")
        raise
    finally:
        DB.close()
