from copy import deepcopy
from datetime import date, timedelta
import hashlib
import json
import math
import uuid
from .config import Settings
from .errors import DomainError
from .fixtures import now, products, trip_dates
from .models import CheckoutInput, ControlInput, OrderInput, TripInput
from .providers import Providers
from .repository import Repository

class CampService:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.repo = Repository(settings.db_path)
        self.providers = Providers(settings)

    @staticmethod
    def validate_dates(checkin: date, checkout: date):
        if checkout <= checkin:
            raise DomainError("체크아웃은 체크인 다음 날 이후로 선택해 주세요.", 422)
        if checkin < date.today():
            raise DomainError("지난 날짜에는 캠핑 일정을 만들 수 없습니다.", 422)
        if (checkout - checkin).days > 30:
            raise DomainError("최대 30박까지 선택해 주세요.", 422)

    async def campsite(self, site_id: str):
        site = next((s for s in await self.providers.catalog() if s["id"] == site_id), None)
        if site is None:
            raise DomainError("캠핑장을 찾을 수 없습니다. 데이터 공급자와 검색 조건을 확인해 주세요.", 404)
        return site

    async def search(self, region="", checkin=None, checkout=None, guests=2, car_camping=None, facilities=""):
        if checkin is not None or checkout is not None:
            if checkin is None or checkout is None:
                raise DomainError("체크인과 체크아웃을 함께 선택해 주세요.", 422)
            self.validate_dates(checkin, checkout)
        all_items = await self.providers.catalog()
        wanted = {f.strip() for f in facilities.split(",") if f.strip()}
        known_facilities = {"shower", "electricity", "pets", "toilet", "water"}
        if not wanted <= known_facilities:
            raise DomainError("지원하지 않는 시설 필터입니다.", 422)
        items = [s for s in all_items if (not region or region in s["region"] or region in s["name"]) and (car_camping is None or s["carCampingAllowed"] is car_camping) and wanted <= set(s["facilities"])]
        if checkin and checkout:
            for site in items:
                site["availability"] = self.availability_for(site, checkin, checkout, guests)
        return {"items": items, "total": len(items), "source": deepcopy(self.providers.catalog_status), "mode": self.providers.catalog_status["mode"]}

    def availability_for(self, site, checkin, checkout, guests):
        result = deepcopy(site["availability"])
        result.update(checkIn=checkin.isoformat(), checkOut=checkout.isoformat(), guests=guests, checkedAt=now())
        if result["demo"]:
            result["coverage"] = f"데모 · {checkin.isoformat()}–{checkout.isoformat()} 전체 {int((checkout-checkin).days)}박"
            if guests > 6:
                result.update(status="unavailable", remaining=0, coverage="데모 사이트 최대 6명")
        return result

    async def availability(self, data: TripInput):
        self.validate_dates(data.checkIn, data.checkOut)
        return self.availability_for(await self.campsite(data.campsiteId), data.checkIn, data.checkOut, data.guests)

    async def save_trip(self, data: TripInput):
        available = await self.availability(data)
        if available["status"] == "unavailable":
            raise DomainError("선택한 일정과 인원의 자리가 없습니다. 다른 장소나 날짜를 선택해 주세요.", 409)
        trip = data.model_dump(mode="json") | {"navigationStarted": False, "updatedAt": now()}
        return self.repo.transact(lambda state: self._save(state, "trip", trip))

    @staticmethod
    def _save(state, key, value):
        state[key] = value
        return deepcopy(value)

    async def weather(self, campsite_id=None, checkin=None, checkout=None):
        trip = self.repo.read()["trip"]
        campsite_id = campsite_id or (trip["campsiteId"] if trip else None)
        default_in, default_out = trip_dates()
        checkin = checkin or date.fromisoformat(trip["checkIn"] if trip else default_in)
        checkout = checkout or date.fromisoformat(trip["checkOut"] if trip else default_out)
        if checkin < date.today():
            return {"source": "보관된 여행 일정", "updatedAt": now(), "summary": "지난 여행은 현재 예보를 제공하지 않습니다.", "overnightLow": None, "rainProbability": None, "forecast": [], "demo": self.settings.mode == "demo", "status": "unavailable", "message": "새 캠핑 일정을 선택하면 날씨를 확인할 수 있습니다."}
        self.validate_dates(checkin, checkout)
        try:
            site = await self.campsite(campsite_id) if campsite_id else None
        except DomainError:
            site = None
        return await self.providers.weather(site, checkin, checkout)

    async def bootstrap(self):
        sites = await self.providers.catalog()
        state = self.repo.read()
        trip = state["trip"]
        if trip and not any(s["id"] == trip["campsiteId"] for s in sites):
            trip = None
        weather = await self.weather(trip["campsiteId"] if trip else None)
        provider_modes = {self.providers.catalog_status["mode"], "demo", "demo" if weather["demo"] else "live"}
        return {"mode": "mixed" if len(provider_modes) > 1 else "demo", "campsites": sites, "trip": trip, "weather": weather, "vehicle": state["vehicle"], "products": self.product_list(state), "cart": self.cart_view(state), "orders": state["orders"], "providers": [deepcopy(self.providers.catalog_status), deepcopy(self.providers.weather_status), {"name": "Vehicle / Navigation / Commerce", "configured": True, "status": "demo", "mode": "demo", "message": "명시적 시뮬레이터 · 실제 차량 제어/예약/결제/배송은 수행하지 않습니다."}]}

    async def navigation(self, campsite_id, start=False):
        site = await self.campsite(campsite_id)
        state = self.repo.read()
        # Fixed origin for the simulator; not a user's geolocation or actual route.
        lat, lon = site.get("latitude"), site.get("longitude")
        if lat is None or lon is None:
            raise DomainError("캠핑장 좌표가 없어 경로를 미리 볼 수 없습니다.", 422)
        a1, a2 = math.radians(37.5665), math.radians(lat)
        dlat, dlon = a2 - a1, math.radians(lon - 126.978)
        hav = math.sin(dlat / 2) ** 2 + math.cos(a1) * math.cos(a2) * math.sin(dlon / 2) ** 2
        distance = round(max(12, 6371 * 2 * math.atan2(math.sqrt(hav), math.sqrt(max(0, 1-hav))) * 1.25), 1)
        consumption = round(distance * 0.17, 1)
        arrival = max(0, round(state["vehicle"]["soc"] - consumption / 77 * 100))
        preview = {"distanceKm": distance, "durationMinutes": round(distance / 68 * 60), "consumptionKwh": consumption, "arrivalSoc": arrival, "chargingStop": arrival < 20, "mode": "demo", "message": "서울 출발 고정 시뮬레이션 · 실제 내비 경로/교통/차량 효율이 아닙니다."}
        if not start:
            return preview
        trip = state["trip"]
        if not trip or trip["campsiteId"] != campsite_id:
            raise DomainError("내 캠핑에 목적지를 먼저 저장해 주세요.", 409)
        def update(s):
            s["trip"]["navigationStarted"] = True
            s["trip"]["updatedAt"] = now()
            return deepcopy(s["trip"])
        trip = self.repo.transact(update)
        return {"started": True, "mode": "demo", "message": "길안내 데모를 시작했습니다. 실제 내비 실행은 Pleos 호스트 연결이 필요합니다.", "trip": trip, "preview": preview}

    @staticmethod
    def _apply_control(vehicle, control, value, zone=None, fail_control=None):
        label = f"{zone}.{control}" if zone else control
        if fail_control in {control, label}:
            return {"control": label, "target": value, "status": "failed", "observed": vehicle.get(control) if zone is None else vehicle[zone].get(control), "confirmation": "unverified", "message": "데모 통신 실패: 현재 값을 유지했습니다. 이 항목을 다시 시도할 수 있습니다."}
        if vehicle["motionState"] != "parked":
            return {"control": label, "target": value, "status": "blocked", "observed": None, "confirmation": "unverified", "message": "플랫폼의 정차 확인 신호가 없어 적용하지 않았습니다."}
        if control == "targetTemperature":
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not 16 <= value <= 30:
                raise DomainError("공조 목표 온도는 16–30°C로 선택해 주세요.", 422)
            if zone is not None:
                raise DomainError("이 데모 차량 공조는 전체 실내 구역을 지원합니다.", 422)
            vehicle[control] = value
        elif control in {"readingLight", "ambientBrightness", "blindLevel"}:
            if zone not in {"front", "rear"}:
                raise DomainError("앞좌석 또는 뒷좌석 구역을 선택해 주세요.", 422)
            if control == "readingLight":
                if not isinstance(value, bool):
                    raise DomainError("독서등은 켜짐/꺼짐으로 선택해 주세요.", 422)
            elif isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 100:
                raise DomainError("밝기·차광 값은 0–100 범위로 선택해 주세요.", 422)
            vehicle[zone][control] = value
        elif control == "windowsClosed":
            if not isinstance(value, bool) or zone is not None:
                raise DomainError("창문 상태 제어값을 확인해 주세요.", 422)
            vehicle[control] = value
        else:
            return {"control": label, "target": value, "status": "unsupported", "observed": None, "confirmation": "unverified", "message": "지원하지 않는 제어입니다. 네이티브 SDK의 실제 지원 범위를 확인해야 합니다."}
        observed = vehicle[control] if zone is None else vehicle[zone][control]
        return {"control": label, "target": value, "status": "applied", "observed": observed, "confirmation": "observed", "message": "시뮬레이터 상태 재조회 확인 · 실제 차량 관측이 아닙니다."}

    def control(self, data: ControlInput):
        def update(s):
            result = self._apply_control(s["vehicle"], data.control, data.value, data.zone)
            s["vehicle"]["updatedAt"] = now()
            return {"vehicle": deepcopy(s["vehicle"]), "results": [result], "status": "completed" if result["status"] == "applied" else "blocked" if result["status"] == "blocked" else "partial"}
        return self.repo.transact(update)

    def scene(self, scene_id, fail_control=None):
        scenes = {
            "load": [("targetTemperature",22,None),("readingLight",True,"front"),("readingLight",True,"rear"),("ambientBrightness",80,"front"),("ambientBrightness",80,"rear"),("blindLevel",0,"front"),("blindLevel",0,"rear")],
            "together": [("targetTemperature",22,None),("readingLight",False,"front"),("readingLight",False,"rear"),("ambientBrightness",45,"front"),("ambientBrightness",45,"rear"),("blindLevel",40,"front"),("blindLevel",40,"rear")],
            "split": [("targetTemperature",22,None),("readingLight",True,"front"),("readingLight",False,"rear"),("ambientBrightness",65,"front"),("ambientBrightness",15,"rear"),("blindLevel",25,"front"),("blindLevel",85,"rear")],
            "sleep": [("targetTemperature",21,None),("readingLight",False,"front"),("readingLight",False,"rear"),("ambientBrightness",5,"front"),("ambientBrightness",5,"rear"),("blindLevel",100,"front"),("blindLevel",100,"rear")],
            "departure": [("readingLight",False,"front"),("readingLight",False,"rear"),("ambientBrightness",0,"front"),("ambientBrightness",0,"rear"),("blindLevel",0,"front"),("blindLevel",0,"rear"),("windowsClosed",True,None)],
        }
        def update(s):
            results = [self._apply_control(s["vehicle"], control, value, zone, fail_control) for control, value, zone in scenes[scene_id]]
            complete = all(r["status"] == "applied" for r in results)
            s["vehicle"]["lastSceneId"] = scene_id if complete else None
            s["vehicle"]["updatedAt"] = now()
            return {"vehicle": deepcopy(s["vehicle"]), "results": results, "status": "completed" if complete else "blocked" if all(r["status"] == "blocked" for r in results) else "partial"}
        return self.repo.transact(update)

    @staticmethod
    def product_list(state):
        return [p | {"stock": state["inventory"].get(p["id"], 0)} for p in products()]

    @staticmethod
    def cart_view(state):
        catalog = {p["id"]: p for p in products()}
        items = []
        for product_id, quantity in state["cart"].items():
            product = catalog[product_id]
            items.append({"productId": product_id, "name": product["name"], "quantity": quantity, "unitPrice": product["price"], "lineTotal": quantity * product["price"], "imageUrl": product["imageUrl"]})
        subtotal = sum(item["lineTotal"] for item in items)
        shipping = 0 if subtotal == 0 or subtotal >= 70000 else 3000
        return {"items": items, "subtotal": subtotal, "shipping": shipping, "total": subtotal + shipping}

    def change_cart(self, product_id, quantity=None, add=False, delete=False):
        if product_id not in {p["id"] for p in products()}:
            raise DomainError("상품을 찾을 수 없습니다.", 404)
        def update(s):
            if delete:
                s["cart"].pop(product_id, None)
            else:
                target = quantity + s["cart"].get(product_id, 0) if add else quantity
                if target < 1 or target > s["inventory"].get(product_id, 0):
                    raise DomainError("요청 수량이 재고를 초과했습니다.", 409)
                s["cart"][product_id] = target
            return self.cart_view(s)
        return self.repo.transact(update)

    def _quote(self, state, data: CheckoutInput, site=None):
        cart = self.cart_view(state)
        eta = date.today() + timedelta(days=2)
        reasons = []
        conditions = ["데모 배송입니다. 실제 택배 접수나 결제는 수행하지 않습니다."]
        address = data.address
        if not cart["items"]:
            reasons.append("장바구니에 준비물을 먼저 담아 주세요.")
        if not data.recipientName:
            reasons.append("수령인 이름을 입력해 주세요.")
        if data.destination == "home":
            if not data.address:
                reasons.append("자택 배송 주소를 입력해 주세요.")
            conditions.append("시연용 가상 주소를 사용해 주세요. 자택 기준 예상 배송일입니다.")
        else:
            if site is None:
                reasons.append("배송받을 캠핑장을 선택해 주세요.")
            else:
                address = site["address"] + " · " + site["delivery"]["location"]
                conditions.extend([site["delivery"]["location"], site["delivery"]["instructions"]])
                if not site["delivery"]["acceptsParcels"]:
                    reasons.append("이 캠핑장은 택배 수령을 지원하지 않습니다. 자택 배송을 선택해 주세요.")
            if not data.siteReference:
                reasons.append("캠핑장 사이트 번호 또는 예약 참조를 입력해 주세요.")
            if data.checkIn is None or data.checkOut is None:
                reasons.append("체류 일정을 선택해 주세요.")
            elif data.checkOut <= data.checkIn or (data.checkOut - data.checkIn).days > 30 or data.checkIn < date.today():
                reasons.append("올바른 체류 일정을 선택해 주세요.")
            else:
                # Demo scheduled delivery; never falsely label a live carrier ETA.
                eta = max(eta, data.checkIn)
                conditions.append("데모 예약 배송: 선택한 체크인 날짜에 맞춰 도착일을 계산합니다.")
                if not data.checkIn <= eta < data.checkOut:
                    reasons.append("예상 도착일이 체류 일정과 맞지 않습니다. 자택 배송이나 일정을 변경해 주세요.")
        if any(q > state["inventory"].get(pid, 0) for pid, q in state["cart"].items()):
            reasons.append("일부 상품의 재고가 부족합니다. 수량을 변경해 주세요.")
        return {"eligible": not reasons, "reason": " ".join(reasons) if reasons else None, "destination": data.destination, "eta": eta.isoformat(), "receivingConditions": conditions, "subtotal": cart["subtotal"], "shipping": cart["shipping"], "total": cart["total"], "recipientName": data.recipientName, "address": address, "siteReference": data.siteReference, "phone": data.phone, "mode": "demo"}

    async def quote(self, data: CheckoutInput):
        site = await self.campsite(data.campsiteId) if data.destination == "campsite" and data.campsiteId else None
        return self._quote(self.repo.read(), data, site)

    async def create_order(self, data: OrderInput):
        payload = data.model_dump(mode="json", exclude={"idempotencyKey"})
        fingerprint = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
        # Check idempotency before looking up providers/cart, including retries after checkout.
        stored = self.repo.read()["idempotency"].get(data.idempotencyKey)
        if stored:
            if stored["fingerprint"] != fingerprint:
                raise DomainError("같은 주문 키에 다른 요청이 전달되었습니다. 새 주문으로 다시 확인해 주세요.", 409)
            return next(o for o in self.repo.read()["orders"] if o["id"] == stored["orderId"])
        site = await self.campsite(data.campsiteId) if data.destination == "campsite" and data.campsiteId else None
        def update(s):
            existing = s["idempotency"].get(data.idempotencyKey)
            if existing:
                if existing["fingerprint"] != fingerprint:
                    raise DomainError("같은 주문 키에 다른 요청이 전달되었습니다.", 409)
                return next(o for o in s["orders"] if o["id"] == existing["orderId"])
            quote = self._quote(s, data, site)
            if not quote["eligible"]:
                raise DomainError(quote["reason"], 409)
            order = {"id": "DEMO-" + uuid.uuid4().hex[:10].upper(), "status": "demo_confirmed", "mode": "demo", "createdAt": now(), "items": self.cart_view(s)["items"], "total": quote["total"], "subtotal": quote["subtotal"], "shipping": quote["shipping"], "destination": data.destination, "eta": quote["eta"], "receivingConditions": quote["receivingConditions"], "recipientName": quote["recipientName"], "address": quote["address"], "siteReference": data.siteReference, "phone": data.phone, "campsiteId": data.campsiteId, "checkIn": payload["checkIn"], "checkOut": payload["checkOut"]}
            for pid, quantity in s["cart"].items():
                s["inventory"][pid] -= quantity
            s["orders"].insert(0, order)
            s["idempotency"][data.idempotencyKey] = {"fingerprint": fingerprint, "orderId": order["id"]}
            s["cart"] = {}
            return deepcopy(order)
        return self.repo.transact(update)
