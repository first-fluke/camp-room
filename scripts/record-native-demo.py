#!/usr/bin/env python3
"""Exercise the native Compose app through adb and record its real display.

Start the demo API and an Android emulator, install app-demo-debug.apk, then run
this script. Set CAMP_DEMO_CAPTURE=emulator for host encoding on slow guests. Only the isolated demo fixture database should be used for QA.
"""
from __future__ import annotations

import json
import hashlib
from datetime import datetime, timezone
import os
import re
import shutil
import subprocess
import sys
import time
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
ADB = os.environ.get("ADB_PATH") or shutil.which("adb") or str(SDK / "platform-tools/adb")
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")
APP = "camp.room.app.demo"
API = os.environ.get("CAMP_API_URL", "http://127.0.0.1:8000/api/v1").rstrip("/")
OUT = ROOT / os.environ.get("CAMP_DEMO_OUTPUT_DIR", "artifacts/demo")
QA = ROOT / os.environ.get("CAMP_DEMO_QA_DIR", "artifacts/qa")
CHECKS: list[dict] = []
CAPTIONS: list[dict] = []
START = 0.0
SPEED = float(os.environ.get("CAMP_DEMO_SPEED", "1"))
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
CAPTURE_BACKEND = os.environ.get("CAMP_DEMO_CAPTURE", "android")
LAUNCH_TIMEOUT = int(os.environ.get("CAMP_DEMO_LAUNCH_TIMEOUT", "180"))


def adb(*arguments: str, timeout: int = 35, binary: bool = False):
    result = subprocess.run([ADB, "-s", SERIAL, *map(str, arguments)], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"adb {' '.join(arguments[:3])}: {result.stderr.decode(errors='replace')[-900:]}")
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace").strip()


def api(route: str, method: str = "GET", body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(API + route, data=data, method=method, headers={"Content-Type": "application/json"})
    with HTTP.open(request, timeout=15) as response:
        return json.load(response)


def check(name: str, condition: bool, detail: str = ""):
    if not condition:
        raise AssertionError(f"{name}: {detail}")
    CHECKS.append({"name": name, "status": "passed", "detail": detail})
    print(f"PASS {name}", flush=True)


def poll(predicate, timeout: int = 45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.5)
    raise AssertionError("Expected app/server state was not reached before timeout")


def pause(seconds: float):
    time.sleep(max(0.1, seconds * SPEED))


def hierarchy():
    # uiautomator can return exit 0 after an idle-window error. A unique path
    # prevents any earlier successful dump from becoming false UI evidence.
    remote_path = f"/sdcard/camp-room-ui-{time.monotonic_ns()}.xml"
    try:
        result = adb("shell", "uiautomator", "dump", "--compressed", remote_path, timeout=45)
        if "UI hierchary dumped to:" not in result and "UI hierarchy dumped to:" not in result:
            raise RuntimeError("UIAutomator did not produce a fresh hierarchy")
        return ET.fromstring(adb("shell", "cat", remote_path, timeout=20))
    finally:
        adb("shell", "rm", "-f", remote_path, timeout=20)


def bounds(node):
    values = list(map(int, re.findall(r"\d+", node.attrib.get("bounds", ""))))
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        return None
    return values


def find_node(*, tag: str | None = None, text: str | None = None, contains: str | None = None, timeout: int = 45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            document = hierarchy()
        except (RuntimeError, ET.ParseError, subprocess.TimeoutExpired):
            pause(1)
            continue
        for node in document.iter("node"):
            identity = node.attrib.get("resource-id", "")
            label = node.attrib.get("text", "")
            description = node.attrib.get("content-desc", "")
            matches = ((tag is not None and (identity == tag or identity.endswith(":id/" + tag)))
                       or (text is not None and (label == text or description == text))
                       or (contains is not None and (contains in label or contains in description)))
            if matches and bounds(node) and node.attrib.get("enabled", "true") == "true":
                return node
        pause(0.5)
    raise AssertionError(f"Native UI element missing: tag={tag}, text={text}, contains={contains}")


def tap(*, tag=None, text=None, contains=None, timeout=45):
    node = find_node(tag=tag, text=text, contains=contains, timeout=timeout)
    left, top, right, bottom = bounds(node)
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    pause(0.8)
    return node


def swipe(up=True, right_panel=False):
    dimensions = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1]
    width, height = map(int, dimensions)
    x = int(width * (0.85 if right_panel else 0.7))
    a, b = int(height * 0.82), int(height * 0.30)
    if not up:
        a, b = b, a
    adb("shell", "input", "swipe", str(x), str(a), str(x), str(b), "450")
    pause(0.7)


def reveal(tag, right_panel=False, attempts=5):
    for index in range(attempts):
        try:
            return find_node(tag=tag, timeout=2)
        except AssertionError:
            if index == attempts - 1:
                raise
            swipe(right_panel=right_panel)


def caption(title: str, text: str, hold=2.5):
    elapsed = int((time.monotonic() - START) * 1000)
    if CAPTIONS:
        CAPTIONS[-1]["end"] = elapsed - 80
    CAPTIONS.append({"start": elapsed, "end": elapsed + int(hold * 1000), "title": title, "text": text})
    (OUT / "demo-timeline.partial.json").write_text(json.dumps({"duration": elapsed, "captions": CAPTIONS}, ensure_ascii=False, indent=2))
    print(f"SCENE {title}", flush=True)
    pause(hold)


def screenshot(name: str):
    # Wait for the visible accessibility window before capturing a software-rendered guest.
    # A successful input event alone does not mean the next Compose frame is displayed.
    document = hierarchy()
    if not any(node.attrib.get("text") or node.attrib.get("content-desc") for node in document.iter("node")):
        raise RuntimeError("No visible UI content; refusing a blank screenshot")
    destination = QA / f"native-{name}.png"
    if CAPTURE_BACKEND == "emulator":
        # Host capture avoids PNG/AVC encoding inside an unaccelerated guest.
        directory = Path(tempfile.mkdtemp(prefix="host-capture-", dir=QA))
        result = adb("emu", "screenrecord", "screenshot", str(directory))
        captures = list(directory.glob("*.png"))
        if "OK" not in result or len(captures) != 1:
            raise RuntimeError("Emulator host screenshot was not produced")
        shutil.copy2(captures[0], destination)
    else:
        destination.write_bytes(adb("exec-out", "screencap", "-p", timeout=30, binary=True))


def installed_apk_sha256():
    path = next(line.removeprefix("package:") for line in adb("shell", "pm", "path", APP).splitlines() if line.endswith("/base.apk"))
    return adb("shell", "sha256sum", path).split()[0]


def reuse_preflight(evidence_path: str):
    evidence = json.loads(Path(evidence_path).read_text())
    if evidence["apkSha256"] != installed_apk_sha256():
        raise AssertionError("Preflight evidence belongs to a different installed APK")
    for entry in evidence["screenshots"]:
        screenshot_path = ROOT / entry["path"]
        if hashlib.sha256(screenshot_path.read_bytes()).hexdigest() != entry["sha256"]:
            raise AssertionError("Preserved preflight screenshot changed")
    expected = {"English app language renders native UI", "Korean app language renders native UI", "Native app remains usable in system night mode"}
    previous = {item["name"]: item for item in evidence["checks"]}
    if not expected <= previous.keys() or any(previous[name]["status"] != "passed" for name in expected):
        raise AssertionError("Required language/night checks are absent from preflight evidence")
    for name in sorted(expected):
        check(name, True, "Reused successful direct checks on the identical installed APK; screenshot hashes verified")



def telemetry_records():
    records = []
    for name in ("events.previous.jsonl", "events.jsonl"):
        try:
            raw = adb("shell", "run-as", APP, "cat", "files/diagnostics/" + name)
        except RuntimeError:
            continue
        records.extend(json.loads(line) for line in raw.splitlines() if line.strip())
    return records


def verify_telemetry():
    records = telemetry_records()
    events = {record["event"] for record in records}
    required = {"camp_app_started", "camp_screen_viewed", "camp_api_completed", "camp_sdk_completed", "camp_action_completed"}
    check("Actual native analytics, API and SDK events are persisted", required <= events)
    actions = {record.get("attributes", {}).get("action") for record in records if record["event"] == "camp_action_completed"}
    check("Saved trip, navigation, scene and checkout analytics are recorded", {"save_trip", "navigation", "apply_scene", "checkout"} <= actions)
    allowed = {"screen", "action", "api", "sdk", "outcome", "duration_ms", "status_code", "error_type", "runtime", "order_mode"}
    check("Native event attributes obey the privacy allowlist", all(set(record.get("attributes", {})) <= allowed for record in records))
    check("Demo checkout analytics preserve runtime and order provenance", any(record.get("attributes", {}).get("action") == "checkout" and record["attributes"].get("runtime") == "demo" and record["attributes"].get("order_mode") == "demo" for record in records))
    previous_crashes = sum(record["event"] == "camp_crash" for record in records)
    # Controlled crash is outside the recorded video and only targets the demo APK.
    adb("shell", "am", "crash", APP, timeout=20)
    poll(lambda: sum(record["event"] == "camp_crash" for record in telemetry_records()) > previous_crashes, timeout=45)
    check("Actual Android fatal exception is persisted locally", True)
    crashes = [record["crash"] for record in telemetry_records() if record["event"] == "camp_crash"]
    check("Crash records omit message, cause and thread data", all(set(crash) == {"type", "frames"} and len(crash["frames"]) <= 32 for crash in crashes))
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-n", APP + "/camp.room.app.MainActivity", timeout=30)
    find_node(tag="nav-trip", timeout=LAUNCH_TIMEOUT)
    (QA / "native-telemetry.json").write_text(json.dumps({"status": "passed", "events": sorted(events), "actions": sorted(value for value in actions if value), "controlledCrashObserved": True, "cloudDelivery": "Not inferred from local records; requires provider configuration and dashboard evidence"}, indent=2))


def srt_time(value: int):
    hours, rest = divmod(max(0, value), 3600000)
    minutes, rest = divmod(rest, 60000)
    seconds, millis = divmod(rest, 1000)
    return f"{hours:02}:{minutes:02}:{seconds:02},{millis:03}"


def write_subtitles():
    duration = int((time.monotonic() - START) * 1000)
    if CAPTIONS:
        CAPTIONS[-1]["end"] = duration
    (OUT / "demo-timeline.json").write_text(json.dumps({"duration": duration, "captions": CAPTIONS}, ensure_ascii=False, indent=2))
    subtitle_path = OUT / "Camp_Room_Demo.ko.srt"
    subtitle_path.write_text("\n".join(f"{i+1}\n{srt_time(c['start'])} --> {srt_time(c['end'])}\n{c['title']}\n{c['text']}\n" for i, c in enumerate(CAPTIONS)), encoding="utf-8")
    return subtitle_path


def stop_recording(process):
    try:
        pids = adb("shell", "pidof", "screenrecord").split()
        for pid in pids:
            if pid.isdigit():
                adb("shell", "kill", "-2", pid)
        process.wait(timeout=30)
    except (RuntimeError, subprocess.TimeoutExpired):
        process.terminate()
        process.wait(timeout=10)


def run():
    global START
    OUT.mkdir(parents=True, exist_ok=True)
    QA.mkdir(parents=True, exist_ok=True)
    bootstrap = api("/bootstrap")
    check("Recording operates only on explicit demo data", bootstrap["mode"] == "demo")
    check("Actual Android device booted", adb("shell", "getprop", "sys.boot_completed") == "1")
    check("Native demo APK installed", APP in adb("shell", "pm", "list", "packages", APP))
    apk_sha256 = installed_apk_sha256()
    source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    started_at_utc = datetime.now(timezone.utc).isoformat()
    for item in bootstrap["cart"]["items"]:
        api(f"/cart/items/{item['productId']}", "DELETE")
    site = next(s for s in bootstrap["campsites"] if s["id"] == "byeolbit")
    orders_before = len(bootstrap["orders"])
    order_ids_before = {order["id"] for order in bootstrap["orders"]}
    for name in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
        adb("shell", "settings", "put", "global", name, "0")
    adb("shell", "settings", "put", "system", "show_touches", "1")
    adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "0")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-n", APP + "/camp.room.app.MainActivity", timeout=30)
    find_node(tag="campsite-card-byeolbit", timeout=LAUNCH_TIMEOUT)
    preflight_evidence = os.environ.get("CAMP_DEMO_PREFLIGHT_EVIDENCE")
    if preflight_evidence:
        reuse_preflight(preflight_evidence)
        find_node(text="전국 모든 지역")
        adb("shell", "cmd", "uimode", "night", "no")
        screenshot("discover")
    else:
        # Exercise both supported locales through the real settings UI before capture.
        tap(tag="language-settings")
        tap(tag="language-en")
        find_node(tag="campsite-card-byeolbit", timeout=90)
        screenshot("english")
        english_ui = ET.tostring(hierarchy(), encoding="unicode")
        check("English app language renders native UI", "Discover" in english_ui or "Find your" in english_ui or "Find Your" in english_ui)
        tap(tag="language-settings")
        tap(tag="language-ko")
        find_node(tag="campsite-card-byeolbit", timeout=90)
        find_node(text="전국 모든 지역")
        check("Korean app language renders native UI", True)
        adb("shell", "cmd", "uimode", "night", "yes")
        pause(3)
        find_node(tag="campsite-card-byeolbit", timeout=90)
        screenshot("dark")
        check("Native app remains usable in system night mode", True)
        adb("shell", "cmd", "uimode", "night", "no")
        pause(3)
        find_node(tag="campsite-card-byeolbit", timeout=90)
        screenshot("discover")
    recorder_log = (QA / "native-screenrecord.log").open("w")
    START = time.monotonic()
    if CAPTURE_BACKEND == "emulator":
        raw = OUT / "Camp_Room_Native_Raw.webm"
        response = adb("emu", "screenrecord", "start", "--fps", "12", "--bit-rate", "3M", "--time-limit", "1800", str(raw))
        if "OK" not in response:
            raise RuntimeError("Emulator host recording could not start")
        recorder_log.write(response)
        recorder = None
    else:
        raw = OUT / "Camp_Room_Native_Raw.mp4"
        recorder = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord", "--bit-rate", "6M", "--time-limit", "0", "/sdcard/camp-room-native.mp4"], stdout=recorder_log, stderr=subprocess.STDOUT)
    try:
        caption("캠프룸", "캠핑장을 찾는 순간부터 내 차에서 쉬는 시간까지.", 3.5)
        tap(text="전국 모든 지역")
        tap(text="강원")
        tap(text="샤워실")
        tap(tag="search-campsites")
        caption("01  캠핑장 찾기", "지역과 시설, 숙박 일정으로 캠핑장을 고릅니다.", 3)
        document = hierarchy()
        visible_cards = [n for n in document.iter("node") if n.attrib.get("resource-id", "").startswith("campsite-card-")]
        check("Native filters return matching campsite cards", bool(visible_cards))
        tap(tag="campsite-card-byeolbit")
        caption("장소 정보와 예약 가능 여부", "시설·차박 규칙·잔여 자리와 데이터 출처를 함께 확인합니다.", 4)
        screenshot("campsite-detail")
        reveal("save-trip")
        tap(tag="save-trip")
        poll(lambda: api("/trip")["campsiteId"] == site["id"])
        check("Native save persists selected trip", api("/trip")["campsiteId"] == site["id"])
        caption("02  내 캠핑", "선택한 장소의 날씨와 이동 계획이 내 캠핑으로 이어집니다.", 4)
        find_node(tag="trip-summary", timeout=90)
        screenshot("trip")
        reveal("start-navigation")
        tap(tag="start-navigation")
        poll(lambda: api("/trip")["navigationStarted"])
        check("Native navigation action reaches backend demo intent", api("/trip")["navigationStarted"])
        caption("날씨와 차량 상태를 함께", "예보와 실내 온도를 구분하고, 배터리와 예상 이동 정보를 살펴봅니다.", 3)
        tap(tag="nav-cabin")
        # Main tabs retain the content scroll offset; begin this scene at its actual heading.
        for _ in range(5):
            swipe(up=False)
        caption("03  차박 공간 설정", "앞쪽은 독서, 뒤쪽은 휴식. 구역마다 다른 환경을 만듭니다.", 4)
        reveal("scene-split", attempts=8)
        tap(tag="scene-split")
        reveal("apply-scene")
        tap(tag="apply-scene")
        poll(lambda: api("/vehicle")["lastSceneId"] == "split")
        vehicle = api("/vehicle")
        check("Native cabin scene applies distinct front and rear states", vehicle["front"] != vehicle["rear"])
        caption("항목별 적용 결과", "설정 요청과 관측값을 구분해 확인합니다. 영상의 차량은 시뮬레이터입니다.", 4)
        screenshot("cabin")
        before_preview = api("/vehicle")["updatedAt"]
        tap(tag="voice-open")
        tap(text="독서와 휴식")
        tap(tag="voice-preview")
        reveal("voice-preview-open")
        check("Command preview does not apply vehicle changes", api("/vehicle")["updatedAt"] == before_preview)
        caption("말이나 문장을 설정 미리보기로", "예시 문장을 직접 선택한 화면입니다. 차량 설정은 확인 후 별도로 적용합니다.", 4)
        screenshot("command-preview")
        tap(tag="voice-preview-open")
        tap(tag="nav-trip")
        reveal("product-add-sleeping-bag")
        caption("04  캠핑 준비물", "캠핑 조건에 맞는 준비물을 같은 앱에서 장바구니에 담습니다.", 3)
        tap(tag="product-add-sleeping-bag")
        reveal("product-add-camp-lantern")
        tap(tag="product-add-camp-lantern")
        poll(lambda: len(api("/cart")["items"]) == 2)
        check("Native cart additions persist with server prices", api("/cart")["subtotal"] == 118000)
        tap(tag="cart-open")
        caption("05  배송지 선택", "집에서 미리 받거나, 체류할 캠핑장으로 배송지를 선택합니다.", 3)
        tap(tag="delivery-home")
        find_node(tag="delivery-address")
        screenshot("home-delivery")
        pause(3)
        tap(tag="delivery-campsite")
        reveal("delivery-quote", right_panel=True)
        tap(tag="delivery-quote")
        caption("수령 조건과 도착 예정일 확인", "캠핑장 택배 수령 조건과 체류 일정에 맞는 배송인지 확인합니다.", 4)
        reveal("checkout-submit", right_panel=True)
        screenshot("checkout")
        tap(tag="checkout-submit")
        find_node(tag="order-confirmation", timeout=50)
        orders = api("/orders")["items"]
        check("Native demo checkout creates exactly one persisted order", len(orders) == orders_before + 1)
        latest = next(order for order in orders if order["id"] not in order_ids_before)
        if "campsiteId" in latest:
            check("Order delivery campsite matches the trip", latest["campsiteId"] == site["id"])
        check("Order uses campsite delivery and no real payment", latest["destination"] == "campsite" and latest["status"] == "demo_confirmed")
        check("Successful checkout clears the cart", len(api("/cart")["items"]) == 0)
        caption("준비부터 머무는 시간까지, 캠프룸", "주문이 저장됐습니다. 이 시연에서는 실제 결제나 배송이 발생하지 않습니다.", 5)
        screenshot("order")
        reveal("order-back-trip")
        tap(tag="order-back-trip")
        caption("캠프룸", "자연과 나 사이, 작은 방. 실제 Android 앱 화면을 녹화했습니다.", 4)
        write_subtitles()
    finally:
        if CAPTURE_BACKEND == "emulator":
            recorder_log.write(adb("emu", "screenrecord", "stop"))
        else:
            stop_recording(recorder)
        recorder_log.close()
    if CAPTURE_BACKEND != "emulator":
        adb("pull", "/sdcard/camp-room-native.mp4", str(raw), timeout=60)
    check("Real native screen recording exists", raw.exists() and raw.stat().st_size > 10000)
    # Process recreation must preserve the server-backed trip, scenes and orders.
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-n", APP + "/camp.room.app.MainActivity", timeout=30)
    find_node(tag="nav-trip", timeout=LAUNCH_TIMEOUT)
    check("State persists across Android process recreation", api("/trip")["campsiteId"] == site["id"] and len(api("/orders")["items"]) == orders_before + 1)
    verify_telemetry()
    rendered = OUT / "Camp_Room_Demo.mp4"
    node_code = "const {renderVideo}=require('./scripts/demo-media.cjs');const p=process.argv.slice(1);console.log(JSON.stringify(renderVideo(p[0],p[1],p[2],p[3]||undefined)))"
    result = subprocess.run(["node", "-e", node_code, str(raw), str(OUT / "Camp_Room_Demo.ko.srt"), str(rendered), os.environ.get("CAMP_DEMO_FONT_DIR", "")], cwd=ROOT, capture_output=True, text=True, timeout=420)
    if result.returncode:
        raise RuntimeError(result.stderr[-3000:])
    metadata = json.loads(result.stdout.strip().splitlines()[-1])
    (OUT / "demo-metadata.json").write_text(json.dumps({"capture": "Android Emulator host display capture" if CAPTURE_BACKEND == "emulator" else "Android adb screenrecord", "applicationId": APP, "video": metadata}, ensure_ascii=False, indent=2))
    check("Final MP4 is 1920×1080", metadata["streams"][0]["width"] == 1920 and metadata["streams"][0]["height"] == 1080)
    report = {"status": "passed", "platform": "Android 35 native Kotlin/Jetpack Compose", "applicationId": APP, "captureBackend": CAPTURE_BACKEND, "apkSha256": apk_sha256, "nativeSourceCommit": source_commit, "startedAtUtc": started_at_utc, "verifiedAtUtc": datetime.now(timezone.utc).isoformat(), "preflightEvidence": preflight_evidence, "checks": CHECKS, "video": str(rendered.relative_to(ROOT)), "videoSha256": hashlib.sha256(rendered.read_bytes()).hexdigest(), "videoDurationSeconds": float(metadata["format"]["duration"]), "limitations": ["Emulated vehicle and demo booking/payment", "Actual SDK artifact compilation is recorded separately", "External campground/weather provider keys are not configured; source data in this recording is explicitly demo"]}
    (QA / "native-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    try:
        run()
    except Exception as exc:
        QA.mkdir(parents=True, exist_ok=True)
        (QA / "native-results.json").write_text(json.dumps({"status": "failed", "checks": CHECKS, "error": str(exc)}, ensure_ascii=False, indent=2))
        print(f"NATIVE QA FAILED: {exc}", file=sys.stderr, flush=True)
        raise
