"""Normalized Camp Room persistence shared by SQLite and remote Turso.

API-shaped dictionaries are transient views assembled from relational rows.
Only typed columns and child relations are stored; no canonical JSON documents.
"""
from __future__ import annotations
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
from pathlib import Path
import re
import threading
import uuid
from .errors import DomainError
from .fixtures import campsites, initial_state, now, products
from .sql_store import Statement, StorageUnavailable

SCHEMA = Path(__file__).parent / "migrations/001_normalized.sql"


def later(seconds):
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat()


def put(table, data, keys=(), *, ignore=False, where=""):
    columns = tuple(data)
    sql = f"INSERT {'OR IGNORE ' if ignore else ''}INTO {table}({','.join(columns)}) VALUES({','.join('?' for _ in columns)})"
    if keys:
        updates = [column for column in columns if column not in keys]
        sql += f" ON CONFLICT({','.join(keys)}) DO UPDATE SET " + ",".join(f"{column}=excluded.{column}" for column in updates)
        if where: sql += " WHERE " + where
    return Statement(sql, tuple(data[column] for column in columns))


def flag(value):
    return None if value is None else bool(value)


class NormalizedRepository:
    def __init__(self, database, namespace="camp-room", profile_id="default"):
        for value in (namespace, profile_id):
            if not re.fullmatch(r"[A-Za-z0-9_:-]{1,80}", value):
                raise ValueError("Database scope/profile identifiers must contain 1–80 safe characters")
        self.sql = database
        self.namespace = namespace
        self.profile_id = profile_id
        self._initialized = False
        self._init_lock = threading.Lock()

    def _provider(self, statements, name, kind):
        provider_id = kind + "-" + hashlib.sha256(name.encode()).hexdigest()[:16]
        statements.append(put("cr_providers", {"scope_id": self.namespace, "provider_id": provider_id, "name": name, "kind": kind}, ignore=True))
        return provider_id

    def _catalog_statements(self, sites):
        statements = [Statement("UPDATE cr_campsites SET active=0 WHERE scope_id=?", (self.namespace,))]
        for site in sites:
            source = site["source"]
            provider = self._provider(statements, source["name"], "catalog")
            values = {"scope_id": self.namespace, "campsite_id": site["id"], "provider_id": provider,
                      "name": site["name"], "region": site["region"], "address": site["address"],
                      "latitude": site.get("latitude"), "longitude": site.get("longitude"),
                      "nightly_price": site.get("nightlyPrice"), "currency": "KRW",
                      "car_camping_allowed": site.get("carCampingAllowed"), "ev_charging_allowed": site.get("evChargingAllowed"),
                      "description": site["description"], "image_url": site["imageUrl"],
                      "check_in_time": site["checkInTime"], "check_out_time": site["checkOutTime"],
                      "source_updated_at": source["updatedAt"], "source_mode": source["mode"],
                      "provider_modified_at": source.get("providerModifiedAt"), "coverage": source.get("coverage"),
                      "fallback_reason": source.get("fallbackReason"), "active": 1}
            statements.append(put("cr_campsites", values, ("scope_id", "campsite_id")))
            for table in ("cr_campsite_facilities", "cr_campsite_rules"):
                statements.append(Statement(f"DELETE FROM {table} WHERE scope_id=? AND campsite_id=?", (self.namespace, site["id"])))
            for code in site["facilities"]:
                statements.append(put("cr_facilities", {"code": code}, ignore=True))
                statements.append(put("cr_campsite_facilities", {"scope_id": self.namespace, "campsite_id": site["id"], "facility_code": code}))
            for position, text in enumerate(site["rules"]):
                statements.append(put("cr_campsite_rules", {"scope_id": self.namespace, "campsite_id": site["id"], "position": position, "rule_text": text}))
            delivery = site["delivery"]
            statements.append(put("cr_receiving_policies", {"scope_id": self.namespace, "campsite_id": site["id"],
                "accepts_parcels": delivery["acceptsParcels"], "location": delivery["location"], "instructions": delivery["instructions"],
                "source": "provider", "updated_at": now()}, ("scope_id", "campsite_id"), where="cr_receiving_policies.source<>'admin'"))
            availability = site["availability"]
            booking = self._provider(statements, availability["provider"], "booking")
            # A non-demo provider without reservation inventory can only say unknown.
            if not availability["demo"] and availability["status"] != "unknown":
                raise DomainError("날짜별 예약 공급자 없이 실시간 잔여 수를 저장할 수 없습니다.", 422)
            statements.append(put("cr_campsite_availability_overviews", {"scope_id": self.namespace, "campsite_id": site["id"],
                "provider_id": booking, "status": availability["status"], "remaining": availability["remaining"],
                "coverage": availability["coverage"], "checked_at": availability["checkedAt"], "demo": availability["demo"]}, ("scope_id", "campsite_id")))
        return statements

    def _profile_seed(self):
        scope, profile = self.namespace, self.profile_id
        state = initial_state()
        statements = [put("cr_profiles", {"scope_id": scope, "profile_id": profile}, ignore=True), put("cr_carts", {"scope_id": scope, "profile_id": profile}, ignore=True)]
        scenes = {
            "load": ("짐 정리",22,True,True,80,80,0,0,None),
            "together": ("함께 쉬기",22,False,False,45,45,40,40,None),
            "split": ("각자의 시간",22,True,False,65,15,25,85,None),
            "sleep": ("잘 준비",21,False,False,5,5,100,100,None),
            "departure": ("출발 준비",None,False,False,0,0,0,0,True),
        }
        for scene_id, (label,temp,fl,rl,fb,rb,fd,rd,windows) in scenes.items():
            statements.append(put("cr_saved_scenes", {"scope_id": scope,"profile_id": profile,"scene_id": scene_id,"label": label,"target_temperature": temp,"windows_closed": windows}, ignore=True))
            for zone, light, brightness, blind in [("front",fl,fb,fd),("rear",rl,rb,rd)]:
                statements.append(put("cr_saved_scene_zones", {"scope_id": scope,"profile_id": profile,"scene_id": scene_id,"zone_id": zone,"reading_light": light,"ambient_brightness": brightness,"blind_level": blind}, ignore=True))
        statements.append(put("cr_trips", self._trip_row(state["trip"]), ignore=True))
        statements.append(put("cr_vehicle_states", self._vehicle_row(state["vehicle"]), ignore=True))
        for zone in ("front", "rear"):
            statements.append(put("cr_vehicle_zones", self._zone_row(zone, state["vehicle"][zone]), ignore=True))
        return statements

    def initialize(self):
        if self._initialized: return
        with self._init_lock:
            if self._initialized: return
            session = self.sql.session()
            try:
                session.begin(True)
                # Additive migration, including a recorded version. Legacy prototype
                # JSON tables are intentionally untouched and are never read.
                script = "\n".join(line for line in SCHEMA.read_text().splitlines() if not line.lstrip().startswith("--"))
                statements = [Statement(sql.strip()) for sql in script.split(";") if sql.strip() and "PRAGMA foreign_keys" not in sql]
                preparation = statements + [put("cr_schema_migrations", {"version": 1, "applied_at": now()}, ignore=True), Statement("SELECT scope_id FROM cr_scopes WHERE scope_id=?", (self.namespace,))]
                existing = session.run(preparation)[-1].rows
                seed = []
                if not existing:
                    seed = [put("cr_scopes", {"scope_id": self.namespace, "created_at": now()})]
                    seed += self._catalog_statements(campsites())
                    for item in products():
                        seed.append(put("cr_products", {"scope_id": self.namespace,"product_id": item["id"],"name": item["name"],"price": item["price"],"currency": "KRW","category": item["category"],"recommendation_reason": item["recommendationReason"],"image_url": item["imageUrl"]}))
                        seed.append(put("cr_inventory", {"scope_id": self.namespace,"product_id": item["id"],"available": item["stock"]}))
                seed += [put("cr_zones", {"zone_id": zone}, ignore=True) for zone in ("front", "rear")]
                seed += self._profile_seed()
                session.run(seed)
                session.commit()
                self._initialized = True
            finally:
                session.close()

    def _trip_row(self, trip):
        return {"scope_id": self.namespace,"profile_id": self.profile_id,"campsite_id": trip["campsiteId"],"check_in": trip["checkIn"],"check_out": trip["checkOut"],"guests": trip["guests"],"navigation_started": trip["navigationStarted"],"updated_at": trip["updatedAt"]}

    def _vehicle_row(self, vehicle):
        return {"scope_id": self.namespace,"profile_id": self.profile_id,"mode": vehicle["mode"],"motion_state": vehicle["motionState"],"soc": vehicle["soc"],"cabin_temperature": vehicle["cabinTemperature"],"target_temperature": vehicle["targetTemperature"],"doors_closed": vehicle["doorsClosed"],"windows_closed": vehicle["windowsClosed"],"last_scene_id": vehicle["lastSceneId"],"updated_at": vehicle["updatedAt"]}

    def _zone_row(self, zone, data):
        return {"scope_id": self.namespace,"profile_id": self.profile_id,"zone_id": zone,"reading_light": data["readingLight"],"ambient_brightness": data["ambientBrightness"],"blind_level": data["blindLevel"]}

    def _snapshot(self, session, all_profiles=False):
        scope_tables = ["providers", "provider_runs", "campsites", "campsite_facilities", "campsite_rules", "receiving_policies", "campsite_availability_overviews", "products", "inventory", "orders", "order_items", "order_deliveries", "order_receiving_conditions", "order_fulfillment"]
        profile_tables = ["trips", "vehicle_states", "vehicle_zones", "carts", "cart_items", "idempotency_keys", "saved_scenes", "saved_scene_zones"]
        names, queries = [], []
        for table in scope_tables:
            names.append(table)
            queries.append(Statement(f"SELECT * FROM cr_{table} WHERE scope_id=? ORDER BY rowid", (self.namespace,)))
        for table in profile_tables:
            names.append(table)
            queries.append(Statement(f"SELECT * FROM cr_{table} WHERE scope_id=? AND profile_id=? ORDER BY rowid", (self.namespace, self.profile_id)))
        rows = {name: result.dictionaries() for name, result in zip(names, session.run(queries))}
        providers = {row["provider_id"]: row for row in rows["providers"]}
        receiving = {row["campsite_id"]: {"acceptsParcels": bool(row["accepts_parcels"]), "location": row["location"], "instructions": row["instructions"], **({"source": "admin", "updatedAt": row["updated_at"]} if row["source"] == "admin" else {})} for row in rows["receiving_policies"]}
        availability = {row["campsite_id"]: {"status": row["status"],"remaining": row["remaining"],"coverage": row["coverage"],"checkedAt": row["checked_at"],"provider": providers[row["provider_id"]]["name"],"demo": bool(row["demo"])} for row in rows["campsite_availability_overviews"]}
        catalog = []
        facilities_by_site, rules_by_site = {}, {}
        for child in rows["campsite_facilities"]:
            facilities_by_site.setdefault(child["campsite_id"], []).append(child["facility_code"])
        for child in sorted(rows["campsite_rules"], key=lambda r: r["position"]):
            rules_by_site.setdefault(child["campsite_id"], []).append(child["rule_text"])
        for row in rows["campsites"]:
            if not row["active"]: continue
            site_id = row["campsite_id"]
            source = {"name": providers[row["provider_id"]]["name"], "updatedAt": row["source_updated_at"], "mode": row["source_mode"]}
            for column, key in [("provider_modified_at","providerModifiedAt"),("coverage","coverage"),("fallback_reason","fallbackReason")]:
                if row[column] is not None: source[key] = row[column]
            catalog.append({"id": site_id,"name": row["name"],"region": row["region"],"address": row["address"],"latitude": row["latitude"],"longitude": row["longitude"],"nightlyPrice": row["nightly_price"],"facilities": facilities_by_site.get(site_id, []),"carCampingAllowed": flag(row["car_camping_allowed"]),"evChargingAllowed": flag(row["ev_charging_allowed"]),"delivery": receiving[site_id],"description": row["description"],"imageUrl": row["image_url"],"checkInTime": row["check_in_time"],"checkOutTime": row["check_out_time"],"rules": rules_by_site.get(site_id, []),"availability": availability[site_id],"source": source})
        trip = rows["trips"][0] if rows["trips"] else None
        trip = {"campsiteId": trip["campsite_id"],"checkIn": trip["check_in"],"checkOut": trip["check_out"],"guests": trip["guests"],"navigationStarted": bool(trip["navigation_started"]),"updatedAt": trip["updated_at"]} if trip else None
        v = rows["vehicle_states"][0]
        vehicle = {"mode": v["mode"],"motionState": v["motion_state"],"soc": v["soc"],"cabinTemperature": v["cabin_temperature"],"targetTemperature": v["target_temperature"],"doorsClosed": flag(v["doors_closed"]),"windowsClosed": flag(v["windows_closed"]),"lastSceneId": v["last_scene_id"],"updatedAt": v["updated_at"]}
        for row in rows["vehicle_zones"]:
            vehicle[row["zone_id"]] = {"readingLight": bool(row["reading_light"]),"ambientBrightness": row["ambient_brightness"],"blindLevel": row["blind_level"]}
        inventory = {row["product_id"]: row["available"] for row in rows["inventory"]}
        product_list = [{"id": row["product_id"],"name": row["name"],"price": row["price"],"stock": inventory[row["product_id"]],"category": row["category"],"recommendationReason": row["recommendation_reason"],"imageUrl": row["image_url"]} for row in rows["products"]]
        images = {row["id"]: row["imageUrl"] for row in product_list}
        orders = []
        for row in reversed(rows["orders"]):
            if not all_profiles and row["profile_id"] != self.profile_id: continue
            order_id = row["order_id"]
            items = [{"productId": r["product_id"],"name": r["product_name_snapshot"],"quantity": r["quantity"],"unitPrice": r["unit_price"],"lineTotal": r["quantity"]*r["unit_price"],"imageUrl": images[r["product_id"]]} for r in sorted(rows["order_items"],key=lambda r:r["line_number"]) if r["order_id"]==order_id]
            d = next(r for r in rows["order_deliveries"] if r["order_id"]==order_id)
            subtotal = sum(i["lineTotal"] for i in items)
            order = {"id": order_id,"status": row["status"],"mode": row["mode"],"createdAt": row["created_at"],"items": items,"subtotal": subtotal,"shipping": row["shipping_charge"],"total": subtotal+row["shipping_charge"],"destination": d["destination"],"eta": d["eta"],"recipientName": d["recipient_name"],"address": d["address_snapshot"],"siteReference": d["site_reference"],"phone": d["phone"],"campsiteId": d["campsite_id"],"checkIn": d["check_in"],"checkOut": d["check_out"],"receivingConditions": [r["condition_text"] for r in sorted(rows["order_receiving_conditions"],key=lambda r:r["position"]) if r["order_id"]==order_id]}
            f = next((r for r in rows["order_fulfillment"] if r["order_id"]==order_id),None)
            if f: order["fulfillment"] = {"status": f["status"],"adminNote": f["admin_note"],"updatedAt": f["updated_at"],"mode": "demo"}
            orders.append(order)
        scenes = {}
        for row in rows["saved_scenes"]:
            controls = []
            if row["target_temperature"] is not None: controls.append(("targetTemperature",row["target_temperature"],None))
            for z in rows["saved_scene_zones"]:
                if z["scene_id"]==row["scene_id"]:
                    controls.extend([("readingLight",bool(z["reading_light"]),z["zone_id"]),("ambientBrightness",z["ambient_brightness"],z["zone_id"]),("blindLevel",z["blind_level"],z["zone_id"])])
            if row["windows_closed"] is not None: controls.append(("windowsClosed",bool(row["windows_closed"]),None))
            scenes[row["scene_id"]] = controls
        statuses = {row["provider_id"]: {"status": {"name": providers[row["provider_id"]]["name"],"configured": bool(row["configured"]),"status": row["status"],"mode": row["mode"],"message": row["message"]},"expiresAt": row["expires_at"],"variant": row["variant"]} for row in rows["provider_runs"]}
        return {"trip": trip,"vehicle": vehicle,"cart": {r["product_id"]:r["quantity"] for r in rows["cart_items"]},"orders": orders,"idempotency": {r["request_key"]:{"fingerprint":r["fingerprint"],"orderId":r["order_id"]} for r in rows["idempotency_keys"]},"inventory":inventory,"products":product_list,"catalog":catalog,"receivingOverrides":{key:value for key,value in receiving.items() if value.get("source")=="admin"},"providerStatuses": statuses,"scenes": scenes}

    def read(self, *, all_profiles=False):
        self.initialize()
        session = self.sql.session()
        try:
            session.begin(False)
            return self._snapshot(session, all_profiles)
        finally:
            session.close()

    def _changes(self, before, after):
        scope, profile = self.namespace, self.profile_id
        writes = []
        if before["trip"] != after["trip"]:
            if after["trip"] is None:
                writes.append(Statement("DELETE FROM cr_trips WHERE scope_id=? AND profile_id=?",(scope,profile)))
            else: writes.append(put("cr_trips",self._trip_row(after["trip"]),("scope_id","profile_id")))
        if before["vehicle"] != after["vehicle"]:
            writes.append(put("cr_vehicle_states",self._vehicle_row(after["vehicle"]),("scope_id","profile_id")))
            for zone in ("front","rear"):
                if before["vehicle"][zone] != after["vehicle"][zone]:
                    writes.append(put("cr_vehicle_zones",self._zone_row(zone,after["vehicle"][zone]),("scope_id","profile_id","zone_id")))
        for product_id in before["inventory"] | after["inventory"]:
            delta = after["inventory"].get(product_id,0)-before["inventory"].get(product_id,0)
            if delta<0:
                writes.append(Statement("UPDATE cr_inventory SET available=available-? WHERE scope_id=? AND product_id=? AND available>=?",(-delta,scope,product_id,-delta),expected=1))
            elif delta>0:
                writes.append(Statement("UPDATE cr_inventory SET available=available+? WHERE scope_id=? AND product_id=?",(delta,scope,product_id),expected=1))
        for product_id in before["cart"] | after["cart"]:
            if product_id not in after["cart"]:
                writes.append(Statement("DELETE FROM cr_cart_items WHERE scope_id=? AND profile_id=? AND product_id=?",(scope,profile,product_id)))
            elif before["cart"].get(product_id) != after["cart"][product_id]:
                writes.append(put("cr_cart_items",{"scope_id":scope,"profile_id":profile,"product_id":product_id,"quantity":after["cart"][product_id]},("scope_id","profile_id","product_id")))
        old_orders = {order["id"]:order for order in before["orders"]}
        for order in after["orders"]:
            old = old_orders.get(order["id"])
            if old is None:
                writes.append(put("cr_orders",{"scope_id":scope,"order_id":order["id"],"profile_id":profile,"status":order["status"],"mode":order["mode"],"created_at":order["createdAt"],"shipping_charge":order["shipping"],"currency":"KRW"}))
                for index,item in enumerate(order["items"]):
                    writes.append(put("cr_order_items",{"scope_id":scope,"order_id":order["id"],"line_number":index,"product_id":item["productId"],"product_name_snapshot":item["name"],"unit_price":item["unitPrice"],"quantity":item["quantity"]}))
                writes.append(put("cr_order_deliveries",{"scope_id":scope,"order_id":order["id"],"destination":order["destination"],"eta":order["eta"],"recipient_name":order["recipientName"],"address_snapshot":order["address"],"site_reference":order["siteReference"],"phone":order["phone"],"campsite_id":order["campsiteId"],"check_in":order["checkIn"],"check_out":order["checkOut"]}))
                for index,text in enumerate(order["receivingConditions"]):
                    writes.append(put("cr_order_receiving_conditions",{"scope_id":scope,"order_id":order["id"],"position":index,"condition_text":text}))
            elif {k:v for k,v in old.items() if k!="fulfillment"} != {k:v for k,v in order.items() if k!="fulfillment"}:
                raise DomainError("확정된 주문 내용은 변경할 수 없습니다.",409)
            if order.get("fulfillment") is not None and (old or {}).get("fulfillment")!=order["fulfillment"]:
                f=order["fulfillment"]
                writes.append(put("cr_order_fulfillment",{"scope_id":scope,"order_id":order["id"],"status":f["status"],"admin_note":f["adminNote"],"updated_at":f["updatedAt"]},("scope_id","order_id")))
        for key,value in after["idempotency"].items():
            if key not in before["idempotency"]:
                writes.append(put("cr_idempotency_keys",{"scope_id":scope,"profile_id":profile,"request_key":key,"fingerprint":value["fingerprint"],"order_id":value["orderId"]}))
        for site_id,delivery in after["receivingOverrides"].items():
            if before["receivingOverrides"].get(site_id)!=delivery:
                writes.append(put("cr_receiving_policies",{"scope_id":scope,"campsite_id":site_id,"accepts_parcels":delivery["acceptsParcels"],"location":delivery["location"],"instructions":delivery["instructions"],"source":"admin","updated_at":delivery["updatedAt"]},("scope_id","campsite_id")))
        return writes

    def transact(self, change, *, all_profiles=False):
        self.initialize()
        session = self.sql.session()
        try:
            session.begin(True)
            before = self._snapshot(session, all_profiles)
            after = deepcopy(before)
            result = change(after)
            session.run(self._changes(before,after))
            session.commit()
            return result
        finally:
            session.close()

    def _status_statements(self, name, kind, status, variant, seconds):
        provider_id="status-"+name
        return [put("cr_providers",{"scope_id":self.namespace,"provider_id":provider_id,"name":status["name"],"kind":kind},("scope_id","provider_id")),put("cr_provider_runs",{"scope_id":self.namespace,"provider_id":provider_id,"configured":status["configured"],"status":status["status"],"mode":status["mode"],"message":status["message"],"completed_at":now(),"expires_at":later(seconds),"variant":variant},("scope_id","provider_id"))]

    def _lease_check(self, session, lease):
        if lease is None: return
        job_key,owner=lease
        result=session.run([Statement("SELECT lease_owner FROM cr_refresh_leases WHERE scope_id=? AND job_key=? AND lease_owner=? AND expires_at>?",(self.namespace,job_key,owner,now()))])[0]
        if not result.rows: raise DomainError("갱신 작업의 임대 시간이 만료되었습니다.",409)

    def store_provider_status(self, name, kind, status, variant, *, lease=None, ttl_seconds=300):
        self._write(self._status_statements(name, kind, status, variant, ttl_seconds), lease=lease)

    def store_catalog(self, sites, status, variant, *, lease=None, ttl_seconds=600):
        self.initialize()
        session=self.sql.session()
        try:
            session.begin(True)
            self._lease_check(session,lease)
            session.run(self._catalog_statements(sites)+self._status_statements("catalog","catalog",status,variant,ttl_seconds))
            session.commit()
        finally: session.close()

    def store_availability(self, site_id, checkin, checkout, guests, value):
        self.initialize()
        statements=[]
        provider=self._provider(statements,value["provider"],"booking")
        statements.append(put("cr_availability_queries",{"scope_id":self.namespace,"campsite_id":site_id,"check_in":checkin,"check_out":checkout,"guests":guests,"slot_type":"camping","provider_id":provider,"status":value["status"],"remaining":value["remaining"],"coverage":value["coverage"],"checked_at":value["checkedAt"],"expires_at":later(60),"demo":value["demo"]},("scope_id","campsite_id","check_in","check_out","guests","slot_type")))
        self._write(statements)

    @staticmethod
    def weather_key(site_id,checkin,checkout):
        return hashlib.sha256(f"{site_id}\n{checkin}\n{checkout}".encode()).hexdigest()

    def cached_weather(self, site_id, checkin, checkout, variant, *, allow_stale=False):
        self.initialize()
        session=self.sql.session()
        try:
            session.begin(False)
            key=self.weather_key(site_id,checkin,checkout)
            results=session.run([Statement("SELECT w.*,p.name AS source_name FROM cr_weather_queries w JOIN cr_providers p ON p.scope_id=w.scope_id AND p.provider_id=w.provider_id WHERE w.scope_id=? AND w.query_key=? AND w.expires_at>? AND w.variant=?",(self.namespace,key,"" if allow_stale else now(),variant)),Statement("SELECT * FROM cr_weather_forecasts WHERE scope_id=? AND query_key=? ORDER BY forecast_time",(self.namespace,key)),Statement("SELECT r.*,p.name FROM cr_provider_runs r JOIN cr_providers p ON p.scope_id=r.scope_id AND p.provider_id=r.provider_id WHERE r.scope_id=? AND r.provider_id='status-weather'",(self.namespace,))])
            if not results[0].rows: return None
            row=results[0].dictionaries()[0]
            value={"source":row["source_name"],"updatedAt":row["updated_at"],"summary":row["summary"],"overnightLow":row["overnight_low"],"rainProbability":row["rain_probability"],"demo":bool(row["demo"]),"status":row["status"],"forecast":[{"time":r["forecast_time"],"temperature":r["temperature"],"condition":r["condition"],"rainProbability":r["rain_probability"]} for r in results[1].dictionaries()]}
            if row["message"] is not None:value["message"]=row["message"]
            if row["issued_at"] is not None:value["issuedAt"]=row["issued_at"]
            if row["grid_nx"] is not None:value["grid"]={"nx":row["grid_nx"],"ny":row["grid_ny"]}
            status=results[2].dictionaries()[0] if results[2].rows else None
            return value, ({"name":status["name"],"configured":bool(status["configured"]),"status":status["status"],"mode":status["mode"],"message":status["message"]} if status else None)
        finally:session.close()

    def store_weather(self, site_id, checkin, checkout, value, status, variant, *, lease=None):
        self.initialize()
        statements=[]
        provider=self._provider(statements,value["source"],"weather")
        key=self.weather_key(site_id,checkin,checkout)
        grid=value.get("grid") or {}
        statements.append(put("cr_weather_queries",{"scope_id":self.namespace,"query_key":key,"campsite_id":site_id,"check_in":checkin,"check_out":checkout,"provider_id":provider,"updated_at":value["updatedAt"],"expires_at":later(300),"variant":variant,"summary":value["summary"],"overnight_low":value["overnightLow"],"rain_probability":value["rainProbability"],"demo":value["demo"],"status":value["status"],"message":value.get("message"),"issued_at":value.get("issuedAt"),"grid_nx":grid.get("nx"),"grid_ny":grid.get("ny")},("scope_id","query_key")))
        statements.append(Statement("DELETE FROM cr_weather_forecasts WHERE scope_id=? AND query_key=?",(self.namespace,key)))
        for forecast in value["forecast"]:
            statements.append(put("cr_weather_forecasts",{"scope_id":self.namespace,"query_key":key,"forecast_time":forecast["time"],"temperature":forecast["temperature"],"condition":forecast["condition"],"rain_probability":forecast["rainProbability"]}))
        status_statements=self._status_statements("weather","weather",status,variant,300)
        session=self.sql.session()
        try:
            session.begin(True)
            self._lease_check(session,lease)
            previous=session.run([Statement("SELECT status,variant FROM cr_weather_queries WHERE scope_id=? AND query_key=?",(self.namespace,key))])[0].rows
            failed=value["status"]=="unavailable" or status["status"] in {"unavailable","fallback"}
            preserve=failed and previous and previous[0]==("available",variant)
            session.run(status_statements if preserve else statements+status_statements)
            session.commit()
            return not preserve
        finally:session.close()

    def _write(self, statements, *, lease=None):
        self.initialize()
        session=self.sql.session()
        try:
            session.begin(True)
            self._lease_check(session,lease)
            result=session.run(statements)
            session.commit()
            return result
        finally:session.close()

    def begin_refresh(self, job_key="provider-cache", owner=None, lease_seconds=120):
        self.initialize()
        owner=owner or uuid.uuid4().hex
        stamp=now()
        session=self.sql.session()
        try:
            session.begin(True)
            result=session.run([Statement("INSERT INTO cr_refresh_leases(scope_id,job_key,lease_owner,expires_at) VALUES(?,?,?,?) ON CONFLICT(scope_id,job_key) DO UPDATE SET lease_owner=excluded.lease_owner,expires_at=excluded.expires_at WHERE cr_refresh_leases.expires_at<=?",(self.namespace,job_key,owner,later(lease_seconds),stamp))])[0]
            if result.affected!=1:return None
            session.run([put("cr_refresh_runs",{"scope_id":self.namespace,"run_id":owner,"job_key":job_key,"provider_id":None,"started_at":stamp,"finished_at":None,"status":"running","attempts":1,"error_code":None})])
            session.commit()
            return owner
        finally:session.close()

    def finish_refresh(self, run_id, status="succeeded", error_code=None, job_key="provider-cache"):
        if status not in {"succeeded","failed","skipped"}:raise ValueError("Invalid refresh state")
        if error_code is not None and not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}",error_code):raise ValueError("Error code must be a safe diagnostic")
        self._write([Statement("UPDATE cr_refresh_runs SET status=?,finished_at=?,error_code=? WHERE scope_id=? AND run_id=? AND status='running'",(status,now(),error_code,self.namespace,run_id),expected=1),Statement("DELETE FROM cr_refresh_leases WHERE scope_id=? AND job_key=? AND lease_owner=?",(self.namespace,job_key,run_id))])

    def refresh_history(self, limit=20):
        self.initialize()
        return self.sql.execute("SELECT run_id,job_key,started_at,finished_at,status,attempts,error_code FROM cr_refresh_runs WHERE scope_id=? ORDER BY started_at DESC LIMIT ?",(self.namespace,min(max(limit,1),100))).dictionaries()

    def close(self):
        self.sql.close()
