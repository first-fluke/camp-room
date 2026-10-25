import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import AsyncMock
import pytest
from fastapi.testclient import TestClient
from app.config import Settings
from app.main import create_app
from app.providers import GoCampingAdapter, KmaAdapter, ProviderFailure, Providers, grid_for
from app.repository import Repository

@pytest.fixture
def settings(tmp_path):
    return Settings(mode="demo", db_path=tmp_path / "camp.sqlite3")

@pytest.fixture
def client(settings):
    with TestClient(create_app(settings)) as client:
        yield client

def body(client):
    return client.get("/api/v1/bootstrap").json()

def trip_request(client, campsite="solbaram"):
    trip = body(client)["trip"]
    return {"campsiteId": campsite, "checkIn": trip["checkIn"], "checkOut": trip["checkOut"], "guests": 2}

def add(client, product="sleeping-bag", quantity=1):
    return client.post("/api/v1/cart/items", json={"productId": product, "quantity": quantity})

def checkout(client, destination="home", campsite="solbaram"):
    trip = trip_request(client, campsite)
    return {"destination": destination, "campsiteId": campsite, "checkIn": trip["checkIn"], "checkOut": trip["checkOut"], "recipientName": "데모 캠퍼", "address": "서울시 데모로 10, 가상주소", "siteReference": "A-12", "phone": ""}

def test_bootstrap_contract_and_truth(client):
    data = body(client)
    assert data["mode"] == "demo"
    assert len(data["campsites"]) == 6
    assert data["trip"]["campsiteId"] == data["campsites"][0]["id"]
    assert data["weather"]["demo"] and data["vehicle"]["mode"] == "demo"
    assert all("데모" in site["source"]["name"] for site in data["campsites"])
    assert all("bookedCount" not in site["availability"] and "occupancyRate" not in site["availability"] for site in data["campsites"])
    assert data["vehicle"]["cabinTemperature"] != data["weather"]["overnightLow"]

def test_filters_dates_and_unknown_inventory(client):
    result = client.get("/api/v1/campsites", params={"region":"강원","facilities":"shower,electricity","carCamping":"true"})
    assert result.status_code == 200
    assert {s["id"] for s in result.json()["items"]} == {"solbaram", "byeolbit"}
    assert client.get("/api/v1/campsites?region=없는지역").json()["total"] == 0
    assert client.get("/api/v1/campsites?facilities=nuclear").status_code == 422
    assert client.get("/api/v1/campsites?guests=99").status_code == 422
    data = trip_request(client)
    data["checkOut"] = data["checkIn"]
    assert client.post("/api/v1/availability/query", json=data).status_code == 422
    assert client.get("/api/v1/campsites?checkIn=2020-01-01&checkOut=2020-01-02").status_code == 422
    data = trip_request(client, "lake")
    availability = client.post("/api/v1/availability/query", json=data).json()
    assert availability["status"] == "unknown" and availability["remaining"] is None
    assert availability["checkIn"] == data["checkIn"]

def test_save_trip_persists_and_navigation_is_explicit_demo(client, settings):
    request = trip_request(client, "byeolbit")
    saved = client.put("/api/v1/trip", json=request)
    assert saved.status_code == 200
    with TestClient(create_app(settings)) as second:
        assert second.get("/api/v1/trip").json()["campsiteId"] == "byeolbit"
    preview = client.post("/api/v1/navigation/preview", json={"campsiteId":"byeolbit"}).json()
    assert preview["mode"] == "demo" and preview["distanceKm"] > 0
    assert 0 <= preview["arrivalSoc"] <= 78
    assert client.post("/api/v1/navigation/start", json={"campsiteId":"solbaram"}).status_code == 409
    start = client.post("/api/v1/navigation/start", json={"campsiteId":"byeolbit"}).json()
    assert start["trip"]["navigationStarted"] and start["mode"] == "demo"
    assert client.get("/api/v1/trip").json()["navigationStarted"]
    unavailable = trip_request(client, "island")
    assert client.put("/api/v1/trip", json=unavailable).status_code == 409

def test_scene_readback_partial_failure_and_stationarity(client):
    result = client.post("/api/v1/vehicle/scenes", json={"sceneId":"split"}).json()
    vehicle = result["vehicle"]
    assert result["status"] == "completed"
    assert vehicle["front"]["readingLight"] and not vehicle["rear"]["readingLight"]
    assert vehicle["front"]["ambientBrightness"] > vehicle["rear"]["ambientBrightness"]
    assert all(r["confirmation"] == "observed" for r in result["results"])
    # HVAC target changes do not manufacture an instantaneous cabin sensor reading.
    before_temp = vehicle["cabinTemperature"]
    applied = client.post("/api/v1/vehicle/controls", json={"control":"targetTemperature","value":24}).json()
    assert applied["vehicle"]["targetTemperature"] == 24
    assert applied["vehicle"]["cabinTemperature"] == before_temp
    partial = client.post("/api/v1/vehicle/scenes", json={"sceneId":"sleep","failControl":"rear.blindLevel"}).json()
    failed = next(r for r in partial["results"] if r["control"] == "rear.blindLevel")
    assert partial["status"] == "partial" and failed["status"] == "failed"
    assert failed["observed"] == 85 and partial["vehicle"]["lastSceneId"] is None
    assert partial["vehicle"]["front"]["blindLevel"] == 100
    assert client.post("/api/v1/vehicle/controls", json={"control":"ambientBrightness","zone":"front","value":101}).status_code == 422
    assert client.post("/api/v1/vehicle/controls", json={"control":"readingLight","zone":"front","value":1}).status_code == 422
    unsupported = client.post("/api/v1/vehicle/controls", json={"control":"flattenSeats","value":True}).json()
    assert unsupported["results"][0]["status"] == "unsupported"
    client.app.state.service.repo.transact(lambda state: state["vehicle"].update(motionState="unknown"))
    blocked = client.post("/api/v1/vehicle/scenes", json={"sceneId":"sleep"}).json()
    assert blocked["status"] == "blocked"
    assert all(r["observed"] is None for r in blocked["results"])

def test_cart_totals_quantities_and_shipping(client):
    cart = add(client, "camp-lantern", 1).json()
    assert (cart["subtotal"], cart["shipping"], cart["total"]) == (39000, 3000, 42000)
    cart = add(client, "camp-lantern", 1).json()
    assert cart["items"][0]["quantity"] == 2 and cart["shipping"] == 0
    assert client.patch("/api/v1/cart/items/camp-lantern", json={"quantity":31}).status_code == 409
    assert client.patch("/api/v1/cart/items/camp-lantern", json={"quantity":0}).status_code == 422
    assert add(client, "camp-lantern", True).status_code == 422
    assert add(client, "not-a-product").status_code == 404
    cart = client.delete("/api/v1/cart/items/camp-lantern").json()
    assert cart["items"] == [] and cart["total"] == 0

def test_checkout_requires_recipient_and_receiving_eta(client):
    add(client)
    invalid = checkout(client)
    invalid["address"] = ""
    quote = client.post("/api/v1/checkout/quote", json=invalid).json()
    assert not quote["eligible"] and "주소" in quote["reason"]
    invalid["idempotencyKey"] = "no-address-01"
    assert client.post("/api/v1/orders", json=invalid).status_code == 409
    reject = checkout(client, "campsite", "morning")
    quote = client.post("/api/v1/checkout/quote", json=reject).json()
    assert not quote["eligible"] and "택배 수령" in quote["reason"]
    request = checkout(client, "campsite")
    quote = client.post("/api/v1/checkout/quote", json=request).json()
    assert quote["eligible"] and request["checkIn"] <= quote["eta"] < request["checkOut"]
    assert "관리동" in quote["address"] and quote["address"] != request["address"]
    request["checkIn"] = date.today().isoformat()
    request["checkOut"] = (date.today()+timedelta(days=1)).isoformat()
    quote = client.post("/api/v1/checkout/quote", json=request).json()
    assert not quote["eligible"] and "체류 일정" in quote["reason"]

def test_order_idempotency_persistence_and_stock(client, settings):
    add(client, "coffee-kit", 2)
    request = checkout(client, "campsite") | {"idempotencyKey":"retry-order-001"}
    first = client.post("/api/v1/orders", json=request)
    assert first.status_code == 200
    order = first.json()
    assert order["id"].startswith("DEMO-") and order["status"] == "demo_confirmed"
    assert order["total"] == 90000 and order["siteReference"] == "A-12"
    assert order["recipientName"] == "데모 캠퍼" and "관리동" in order["address"]
    assert client.get("/api/v1/cart").json()["items"] == []
    second = client.post("/api/v1/orders", json=request)
    assert second.status_code == 200 and second.json() == order
    assert client.post("/api/v1/orders", json=request | {"recipientName":"다른 사람"}).status_code == 409
    with TestClient(create_app(settings)) as reloaded:
        assert reloaded.get("/api/v1/orders").json()["items"] == [order]
        stock = next(p["stock"] for p in reloaded.get("/api/v1/products").json()["items"] if p["id"] == "coffee-kit")
        assert stock == 10
        assert reloaded.post("/api/v1/orders", json=request).json() == order

def test_concurrent_duplicate_order_is_one_transaction(client):
    add(client)
    request = checkout(client) | {"idempotencyKey":"concurrent-order-01"}
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda _: client.post("/api/v1/orders", json=request), range(2)))
    assert all(r.status_code == 200 for r in responses)
    assert responses[0].json()["id"] == responses[1].json()["id"]
    assert len(client.get("/api/v1/orders").json()["items"]) == 1
    assert next(p["stock"] for p in body(client)["products"] if p["id"] == "sleeping-bag") == 19

def test_repository_transaction_rolls_back(settings):
    repo = Repository(settings.db_path)
    def failed(state):
        state["cart"]["sleeping-bag"] = 2
        raise RuntimeError("expected transaction abort")
    with pytest.raises(RuntimeError):
        repo.transact(failed)
    assert repo.read()["cart"] == {}

def test_live_and_auto_without_credentials_never_fake_live(tmp_path):
    with TestClient(create_app(Settings(mode="live", db_path=tmp_path/"live.sqlite3"))) as client:
        data = body(client)
        assert data["campsites"] == [] and data["trip"] is None
        assert not data["weather"]["demo"] and data["weather"]["status"] == "unavailable"
        assert all(not p["configured"] for p in data["providers"][:2])
    with TestClient(create_app(Settings(mode="auto", db_path=tmp_path/"auto.sqlite3"))) as client:
        data = body(client)
        assert data["campsites"] and data["providers"][0]["status"] == "fallback"
        assert data["campsites"][0]["source"]["mode"] == "demo"
        assert "fallbackReason" in data["campsites"][0]["source"]
        assert "미설정" in data["weather"]["message"]

def test_real_gocamping_normalization_does_not_invent_permission():
    site = GoCampingAdapter.normalize({"contentId":"123","facltNm":"실제 공급자 장소","doNm":"강원특별자치도","mapX":"128.5","mapY":"38.0","autoSiteCo":"20","swrmCo":"2","toiletCo":"3","wtrplCo":"1","animalCmgCl":"가능","sbrsCl":"전기,온수"})
    assert site["source"]["mode"] == "live" and site["availability"]["status"] == "unknown"
    assert site["carCampingAllowed"] is None and site["evChargingAllowed"] is None
    assert site["nightlyPrice"] is None and not site["delivery"]["acceptsParcels"]
    assert {"shower","electricity","pets","toilet","water"} <= set(site["facilities"])

def test_gocamping_live_call_contract_and_encoded_key(monkeypatch):
    calls = []
    async def request(url, params):
        calls.append((url, params))
        return {"response":{"body":{"items":{"item":[{"contentId":"1","facltNm":"캠핑"}]}}}}
    monkeypatch.setattr("app.providers.request_json", request)
    rows = asyncio.run(GoCampingAdapter("not-real%2Bkey%3D").fetch())
    assert calls[0][0] == "https://apis.data.go.kr/B551011/GoCamping/basedList"
    assert calls[0][1]["serviceKey"] == "not-real+key="
    assert calls[0][1]["MobileApp"] == "CampRoom" and calls[0][1]["_type"] == "json"
    assert rows[0]["id"] == "gocamping-1"

def test_kma_grid_and_forecast_range(monkeypatch):
    assert grid_for(37.5665,126.978) == (60,127)
    checkin = date.today()+timedelta(days=1)
    calls=[]
    async def request(url, params):
        calls.append((url,params))
        stamp=checkin.strftime("%Y%m%d")
        return {"response":{"body":{"items":{"item":[{"fcstDate":stamp,"fcstTime":"2100","category":"TMP","fcstValue":"8"},{"fcstDate":stamp,"fcstTime":"2100","category":"POP","fcstValue":"30"},{"fcstDate":stamp,"fcstTime":"2100","category":"SKY","fcstValue":"3"}]}}}}
    monkeypatch.setattr("app.providers.request_json",request)
    adapter=KmaAdapter("fake-hub-key","")
    site={"latitude":37.5665,"longitude":126.978}
    weather=asyncio.run(adapter.fetch(site,checkin,checkin+timedelta(days=1)))
    assert weather["status"] == "available" and not weather["demo"]
    assert weather["overnightLow"] == 8 and weather["rainProbability"] == 30
    assert calls[0][0] == KmaAdapter.HUB and calls[0][1]["authKey"] == "fake-hub-key"
    assert (calls[0][1]["nx"],calls[0][1]["ny"]) == (60,127)
    with pytest.raises(ProviderFailure, match="제공 기간"):
        asyncio.run(adapter.fetch(site,checkin+timedelta(days=10),checkin+timedelta(days=11)))

def test_provider_failures_are_safe_and_explicit(monkeypatch,tmp_path):
    async def failed(self):
        raise ProviderFailure("공급자 연결 오류")
    monkeypatch.setattr(GoCampingAdapter,"fetch",failed)
    providers=Providers(Settings(mode="auto",db_path=tmp_path/"p.sqlite3",gocamping_key="do-not-expose"))
    result=asyncio.run(providers.catalog())
    assert result and providers.catalog_status["status"] == "fallback"
    assert "do-not-expose" not in str(providers.catalog_status)
    providers=Providers(Settings(mode="live",db_path=tmp_path/"l.sqlite3",gocamping_key="do-not-expose"))
    assert asyncio.run(providers.catalog()) == []
    assert providers.catalog_status["status"] == "unavailable"


def test_past_persisted_trip_does_not_break_bootstrap(client):
    client.app.state.service.repo.transact(lambda s: s["trip"].update(checkIn="2020-01-01", checkOut="2020-01-02"))
    response = client.get("/api/v1/bootstrap")
    assert response.status_code == 200
    assert response.json()["weather"]["status"] == "unavailable"


def test_external_request_never_returns_secret_url(monkeypatch):
    import httpx
    from app.providers import request_json
    async def failed(self, url, params):
        request = httpx.Request("GET", url, params=params)
        raise httpx.ConnectError("secret-key-in-url", request=request)
    monkeypatch.setattr(httpx.AsyncClient, "get", failed)
    with pytest.raises(ProviderFailure) as error:
        asyncio.run(request_json("https://example.invalid/provider", {"authKey":"not-real-private-key"}))
    assert "private-key" not in str(error.value) and "secret-key" not in str(error.value)


@pytest.mark.parametrize("payload", [None, [], {"response": []}, {"response":{"header":None}}, {"response":{"header":{"resultCode":"00"},"body":[]}}])
def test_malformed_provider_json_is_safe_failure(monkeypatch, payload):
    import httpx
    from app.providers import request_json
    async def response(self, url, params):
        return httpx.Response(200, json=payload, request=httpx.Request("GET",url))
    monkeypatch.setattr(httpx.AsyncClient,"get",response)
    with pytest.raises(ProviderFailure):
        asyncio.run(request_json("https://example.invalid/provider", {"authKey":"private-test-key"}))


def test_nonfinite_provider_values_remain_unknown():
    from app.providers import safe_float
    assert safe_float("NaN") is None and safe_float("inf") is None
    site=GoCampingAdapter.normalize({"contentId":"bad-coords","mapY":"NaN","mapX":"inf"})
    assert site["latitude"] is None and site["longitude"] is None
    with pytest.raises(ProviderFailure, match="좌표"):
        asyncio.run(KmaAdapter("test","").fetch(site,date.today(),date.today()+timedelta(days=1)))
