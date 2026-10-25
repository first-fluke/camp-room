"""Server-side provider adapters. Credentials and request URLs never enter responses/logs.

Primary references:
https://www.data.go.kr/data/15101933/openapi.do
https://apihub.kma.go.kr/apiList.do?seqApi=10&seqApiSub=286
"""
import asyncio
from copy import deepcopy
from datetime import date, datetime, timedelta
import math
import time
from urllib.parse import unquote
from zoneinfo import ZoneInfo
import httpx
from .config import Settings
from .fixtures import campsites, now

SEOUL = ZoneInfo("Asia/Seoul")

class ProviderFailure(Exception):
    """Safe, non-secret provider diagnostic."""

async def request_json(url: str, params: dict) -> dict:
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(10, connect=4), follow_redirects=False) as client:
            response = await client.get(url, params=params)
            response.raise_for_status()
            payload = response.json()
    except httpx.HTTPStatusError as exc:
        raise ProviderFailure(f"공급자 HTTP {exc.response.status_code}: 서비스 권한과 상태를 확인해 주세요.") from None
    except (httpx.HTTPError, ValueError):
        # Do not format the original exception: request URLs may contain API keys.
        raise ProviderFailure("공급자 연결 또는 응답 형식 오류입니다. 잠시 후 다시 확인해 주세요.") from None
    if not isinstance(payload, dict) or not isinstance(payload.get("response"), dict):
        raise ProviderFailure("공급자 응답 구조가 올바르지 않습니다.")
    header = payload.get("response", {}).get("header", {})
    if not isinstance(header, dict):
        raise ProviderFailure("공급자 응답 헤더가 올바르지 않습니다.")
    if str(header.get("resultCode", "00")) not in {"00", "0000", "0"}:
        # Only expose a safe code, not an arbitrary response body.
        raw_code = str(header.get("resultCode", ""))
        code = raw_code[:8] if raw_code.isdigit() else "unknown"
        raise ProviderFailure(f"공급자가 요청을 거절했습니다(코드 {code}). 활용 신청·인증키·호출량을 확인해 주세요.")
    if not isinstance(payload["response"].get("body"), dict):
        raise ProviderFailure("공급자 응답 본문이 올바르지 않습니다.")
    return payload

def extract_items(payload: dict) -> list[dict]:
    response = payload.get("response", {})
    body = response.get("body", {}) if isinstance(response, dict) else {}
    items = body.get("items", {}) if isinstance(body, dict) else {}
    rows = items.get("item", []) if isinstance(items, dict) else []
    if isinstance(rows, dict):
        rows = [rows]
    return [row for row in rows if isinstance(row, dict)] if isinstance(rows, list) else []

def safe_float(value):
    try:
        number = float(value)
        return number if math.isfinite(number) else None
    except (TypeError, ValueError):
        return None

def positive(value) -> bool:
    number = safe_float(value)
    return number is not None and number > 0

class GoCampingAdapter:
    BASE = "https://apis.data.go.kr/B551011/GoCamping"

    def __init__(self, key: str):
        self.key = unquote(key)  # Portal keys may be supplied encoded or decoded.

    async def fetch(self) -> list[dict]:
        payload = await request_json(self.BASE + "/basedList", {
            "serviceKey": self.key, "MobileOS": "ETC", "MobileApp": "CampRoom",
            "_type": "json", "numOfRows": 100, "pageNo": 1,
        })
        return [self.normalize(row) for row in extract_items(payload) if row.get("contentId")]

    @staticmethod
    def normalize(row: dict) -> dict:
        facilities = []
        if positive(row.get("swrmCo")):
            facilities.append("shower")
        if positive(row.get("toiletCo")):
            facilities.append("toilet")
        if positive(row.get("wtrplCo")):
            facilities.append("water")
        if "전기" in str(row.get("sbrsCl", "")):
            facilities.append("electricity")
        pet = str(row.get("animalCmgCl", ""))
        if pet and "불가" not in pet and "가능" in pet:
            facilities.append("pets")
        return {
            "id": "gocamping-" + str(row.get("contentId", "")),
            "name": row.get("facltNm") or "이름 미제공",
            "region": row.get("doNm") or "지역 미제공",
            "address": row.get("addr1") or "주소 미제공",
            "latitude": safe_float(row.get("mapY")), "longitude": safe_float(row.get("mapX")),
            "description": row.get("intro") or row.get("lineIntro") or "운영자에게 상세 이용 조건을 확인해 주세요.",
            "imageUrl": row.get("firstImageUrl") or "",
            "nightlyPrice": None, "facilities": facilities,
            # Drive-in site count does not prove permission to sleep in a vehicle.
            "carCampingAllowed": None, "evChargingAllowed": None,
            "rules": ["차량 내 숙박·입퇴실·전기차 충전 조건은 운영자 확인이 필요합니다.", "고캠핑은 장소·시설 정보이며 예약 재고 공급자가 아닙니다."],
            "checkInTime": "운영자 확인", "checkOutTime": "운영자 확인",
            "delivery": {"acceptsParcels": False, "location": "운영자 확인 필요", "instructions": "공공 장소 데이터에 택배 수령 조건이 없어 캠핑장 배송을 사용할 수 없습니다."},
            "availability": {"status": "unknown", "remaining": None, "coverage": "예약 공급자 미연결", "checkedAt": now(), "provider": "예약 정보 미제공", "demo": False},
            "source": {"name": "한국관광공사 고캠핑", "updatedAt": now(), "mode": "live", "providerModifiedAt": row.get("modifiedtime"), "coverage": "기본 목록 1페이지, 최대 100곳"},
        }

def grid_for(latitude: float, longitude: float) -> tuple[int, int]:
    """KMA 5km Lambert conformal grid (standard latitudes 30/60, origin126/38)."""
    rad = math.pi / 180
    re = 6371.00877 / 5
    slat1, slat2 = 30 * rad, 60 * rad
    sn = math.log(math.cos(slat1) / math.cos(slat2)) / math.log(math.tan(math.pi / 4 + slat2 / 2) / math.tan(math.pi / 4 + slat1 / 2))
    sf = math.tan(math.pi / 4 + slat1 / 2) ** sn * math.cos(slat1) / sn
    ro = re * sf / math.tan(math.pi / 4 + 38 * rad / 2) ** sn
    ra = re * sf / math.tan(math.pi / 4 + latitude * rad / 2) ** sn
    theta = (longitude - 126) * rad
    theta = (theta + math.pi) % (2 * math.pi) - math.pi
    theta *= sn
    return math.floor(ra * math.sin(theta) + 43 + 0.5), math.floor(ro - ra * math.cos(theta) + 136 + 0.5)

class KmaAdapter:
    HUB = "https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getVilageFcst"
    PORTAL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst"

    def __init__(self, hub_key: str, service_key: str):
        self.hub_key = hub_key
        self.service_key = unquote(service_key)

    async def fetch(self, campsite: dict, checkin: date, checkout: date) -> dict:
        if campsite.get("latitude") is None or campsite.get("longitude") is None:
            raise ProviderFailure("캠핑장 좌표가 제공되지 않아 예보를 조회할 수 없습니다.")
        # Issue-time availability varies; use the latest issue at least one hour old.
        reference = datetime.now(SEOUL) - timedelta(hours=1)
        issues = [2, 5, 8, 11, 14, 17, 20, 23]
        hour = next((h for h in reversed(issues) if h <= reference.hour), None)
        if hour is None:
            reference -= timedelta(days=1)
            hour = 23
        nx, ny = grid_for(campsite["latitude"], campsite["longitude"])
        params = {"pageNo": 1, "numOfRows": 1000, "dataType": "JSON", "base_date": reference.strftime("%Y%m%d"), "base_time": f"{hour:02}00", "nx": nx, "ny": ny}
        if self.hub_key:
            params["authKey"] = self.hub_key
            url = self.HUB
        else:
            params["serviceKey"] = self.service_key
            url = self.PORTAL
        payload = await request_json(url, params)
        buckets = {}
        for row in extract_items(payload):
            try:
                day = datetime.strptime(str(row["fcstDate"]), "%Y%m%d").date()
            except (KeyError, ValueError):
                continue
            if checkin <= day < checkout:
                stamp = day.isoformat() + "T" + str(row.get("fcstTime", "0000"))[:2] + ":00:00+09:00"
                buckets.setdefault(stamp, {})[row.get("category")] = row.get("fcstValue")
        forecast = []
        for stamp, row in sorted(buckets.items()):
            temp = safe_float(row.get("TMP"))
            if temp is None:
                continue
            wet = safe_float(row.get("PTY")) or 0
            forecast.append({"time": stamp, "temperature": temp, "condition": "비/눈" if wet else "흐림" if str(row.get("SKY")) == "4" else "구름 조금" if str(row.get("SKY")) == "3" else "맑음", "rainProbability": safe_float(row.get("POP"))})
        if not forecast:
            raise ProviderFailure("선택한 체류 일정은 현재 단기예보 제공 기간 밖이거나 예보 자료가 없습니다. 출발일이 가까워지면 다시 확인해 주세요.")
        overnight = [r["temperature"] for r in forecast if int(r["time"][11:13]) >= 18 or int(r["time"][11:13]) <= 8]
        pops = [r["rainProbability"] for r in forecast if r["rainProbability"] is not None]
        return {"source": "기상청 단기예보", "updatedAt": now(), "summary": "체류 일정에 해당하는 실제 단기예보입니다.", "overnightLow": min(overnight) if overnight else None, "rainProbability": max(pops) if pops else None, "forecast": forecast, "demo": False, "status": "available", "issuedAt": reference.strftime("%Y-%m-%d") + f"T{hour:02}:00:00+09:00", "grid": {"nx": nx, "ny": ny}}

class Providers:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._catalog = None
        self._catalog_at = 0
        self._catalog_lock = asyncio.Lock()
        self.catalog_status = self._status("GoCamping", bool(settings.gocamping_key))
        self.weather_status = self._status("KMA", bool(settings.kma_hub_key or settings.kma_service_key))
        self._weather_cache = {}

    def _status(self, name, configured):
        return {"name": name, "configured": configured, "status": "demo" if self.settings.mode == "demo" else "ready" if configured else "missing_credentials", "mode": "demo" if self.settings.mode == "demo" else "live", "message": "명시적 데모 모드입니다." if self.settings.mode == "demo" else "서버 인증키가 설정되어 있습니다." if configured else "서버 인증키가 아직 설정되지 않았습니다."}

    async def catalog(self):
        async with self._catalog_lock:
            if self._catalog is not None and time.monotonic() - self._catalog_at < 600:
                return deepcopy(self._catalog)
            if self.settings.mode == "demo":
                items = campsites()
            else:
                try:
                    if not self.settings.gocamping_key:
                        raise ProviderFailure("GoCamping 인증키 미설정 · 활용 신청 후 서버 .env에 설정해 주세요.")
                    items = await GoCampingAdapter(self.settings.gocamping_key).fetch()
                    self.catalog_status.update(status="available", mode="live", message="고캠핑 기본 목록 1페이지(최대100곳), 예약 재고·택배 수령 조건 미제공")
                except ProviderFailure as exc:
                    fallback = self.settings.mode == "auto"
                    items = campsites() if fallback else []
                    self.catalog_status.update(status="fallback" if fallback else "unavailable", mode="demo" if fallback else "live", message=str(exc))
                    for site in items:
                        site["source"]["fallbackReason"] = str(exc)
            self._catalog, self._catalog_at = items, time.monotonic()
            return deepcopy(items)

    async def weather(self, campsite: dict | None, checkin: date, checkout: date):
        if self.settings.mode == "demo":
            return self.demo_weather(checkin)
        key = (campsite["id"] if campsite else None, checkin, checkout)
        cached = self._weather_cache.get(key)
        if cached and time.monotonic() - cached[0] < 300:
            return deepcopy(cached[1])
        try:
            if not (self.settings.kma_hub_key or self.settings.kma_service_key):
                raise ProviderFailure("기상청 인증키 미설정 · 서버 .env에 설정해 주세요.")
            if campsite is None:
                raise ProviderFailure("캠핑장을 선택한 뒤 예보를 확인해 주세요.")
            response = await KmaAdapter(self.settings.kma_hub_key, self.settings.kma_service_key).fetch(campsite, checkin, checkout)
            self.weather_status.update(status="available", mode="live", message="선택한 일정의 실제 기상청 단기예보")
        except ProviderFailure as exc:
            fallback = self.settings.mode == "auto"
            self.weather_status.update(status="fallback" if fallback else "unavailable", mode="demo" if fallback else "live", message=str(exc))
            response = self.demo_weather(checkin) if fallback else {"source": "기상청 · 조회 불가", "updatedAt": now(), "summary": "예보 확인 필요", "overnightLow": None, "rainProbability": None, "forecast": [], "demo": False, "status": "unavailable"}
            response["message"] = str(exc)
        self._weather_cache[key] = (time.monotonic(), response)
        return deepcopy(response)

    @staticmethod
    def demo_weather(checkin: date):
        return {"source": "Camp Room 가상 날씨 · 데모", "updatedAt": now(), "summary": "선선한 가을밤, 포근한 침낭을 준비하세요.", "overnightLow": 9, "rainProbability": 20, "forecast": [{"time": f"{checkin.isoformat()}T{hour:02}:00:00+09:00", "temperature": temp, "condition": condition, "rainProbability": rain} for hour, temp, condition, rain in [(14,19,"맑음",10),(17,16,"구름 조금",10),(20,12,"구름 조금",20),(23,9,"맑음",10)]], "demo": True, "status": "available"}
