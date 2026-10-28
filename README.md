# Camp Room · 캠프룸

캠프룸은 Pleos 차량의 큰 화면에서 캠핑장을 찾고, 여행 일정과 준비물을 정리하며 차량 상태를 확인하는 Kotlin·Jetpack Compose 앱입니다. **찾기 · 내 캠핑 · 차박** 메뉴를 제공합니다.

## 현재 구현 범위

- **찾기:** 지역·날짜·인원·시설 조건, 위치 기반 주변 찾기, 캠핑장 사진과 상세 정보, 검색 결과와 전체 개수 구분, 다음/이전 목록. 서버 커서는 필터·정렬·카탈로그 세대에 묶이며 목록 간 중복과 오래된 요청을 처리합니다.
- **내 캠핑:** 장소와 일정 저장·복원, 시설 정보, 해당 지역·기간의 시간별 예보, 여행 준비 목록. 담기는 여행에 장소를 저장하는 기능입니다.
- **차박:** Pleos SDK가 실제로 제공한 배터리·온도 등 관측값을 표시합니다. 미지원·결측·오래된 값과 요청 목표를 구분하고, 안전 조건을 확인할 수 없는 제어는 비활성화합니다.
- **식사와 준비물:** 삼겹살·소고기·갈비·바비큐 모둠의 예시 이미지, 구성·인분 안내와 준비 체크리스트를 제공합니다. 고기 사진은 AI 생성 예시이며 실제 판매 상품의 사진이 아닙니다.
- **화면:** 접고 펼칠 수 있는 오른쪽 메뉴, 한국어·영어, 주야간 테마, 큰 글씨·좁은 폭 대응, 사진 로딩·실패·빈 URL 대체 표시를 지원합니다.

고캠핑 캠핑장 정보·사진과 기상청 예보는 서버의 실제 API 연결로 확인했습니다. 새 설치에서는 각자 발급받은 서버용 키가 필요하며, 이 저장소에 키를 포함하지 않습니다. 예약·결제·재고·배송 사업자 연동은 제공하지 않습니다. 준비물 선택을 구매나 예약 완료로 처리하지 않습니다.

## 실행 환경과 SDK 검증 범위

| 항목 | 환경 / 범위 |
| --- | --- |
| Android 앱 | JDK 17, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20, compileSdk/targetSdk 35 |
| Pleos 런타임 | 공식 Pleos Connect v2.1.0, Android 14/API 34 ARM64 에뮬레이터, cold boot, RAM 4GB, minSdk 28 |
| API·worker | Python 3.14 이상, uv, FastAPI, SQLite 또는 선택적 Turso |
| 관리자 웹 | Node.js 24, Next.js |
| 데이터 | 고캠핑·기상청 실응답 및 두 페이지 이상 목록, 사진·예보 표시를 로컬에서 검증 |
| SDK | Vehicle 2.3.2, vehicle-data 1.1.11, NaviHelper 3.0.5, FusedLocation 1.1.3, STT/TTS 3.3.1 |

공식 에뮬레이터에서 차량 상태 읽기·콜백과 위치 관련 로그를 조사했습니다. 내비 경로 응답의 지연·목적지 판정, SmartGlass 조회 오류, 음성 인식 품질 및 최신 빌드의 TTS 재검증 공백이 남아 있습니다. 이 소스에는 목적지 일치 정책, 세션 세대·오래된 응답 차단, 읽기 오류 분류·backoff, 음성 입력 진단 개선이 포함됩니다. 단위 테스트 통과를 해당 SDK의 런타임 성공으로 취급하지 않습니다. **실차 제어, 실제 길안내 시작, 전체 SDK 성공은 검증하지 않았습니다.**

`pleos` flavor가 실제 Pleos SDK를 사용합니다. `demo` flavor는 일반 Android 에뮬레이터에서 UI 개발용 시뮬레이터를 사용하며 Pleos 서비스 연결의 대체 검증 수단이 아닙니다. 전용 이미지·SDK 이용 약관은 설치자가 공식 배포 경로에서 확인해야 하며 이미지나 SDK 바이너리를 이 저장소에서 재배포하지 않습니다.

## 빠른 실행

### 1. 서버 설정

기존 설정은 덮어쓰지 말고, 처음 준비할 때만 예시 파일을 복사합니다.

```bash
cp -n .env.example .env
uv sync --all-packages --frozen
.venv/bin/python -m uvicorn app.main:app --app-dir apps/api --host 127.0.0.1 --port 8000
```

`.env`의 주요 설정은 다음과 같습니다. 실제 값은 로컬 비공개 설정이나 서버의 비밀 저장소에만 둡니다.

| 변수 | 용도 |
| --- | --- |
| `CAMP_DATA_MODE` | `demo`는 예시 데이터, `live`는 실제 공급자만 사용, `auto`는 공급자 실패 시 예시 대체가 가능한 개발 모드 |
| `GOCAMPING_SERVICE_KEY` | 공공데이터포털 고캠핑 활용신청 키 |
| `KMA_SERVICE_KEY` | 공공데이터포털 기상청 단기예보 키 |
| `KMA_API_HUB_KEY` | 별도 기상청 API허브를 쓰는 경우에만 사용하며 포털 키와 혼용하지 않음 |
| `CAMP_DB_PATH` | 독립 로컬 SQLite 파일 경로 |
| `TURSO_DATABASE_URL`, `TURSO_AUTH_TOKEN` | 선택적 원격 저장소; 둘을 함께 설정 |
| `CAMP_ADMIN_TOKEN`, `CAMP_ADMIN_PASSWORD`, `CAMP_ADMIN_SESSION_SECRET` | 관리자/API 인증; 비공개 서버 값 |

기본값은 `demo`입니다. 실제 데이터 검증에는 `CAMP_DATA_MODE=live`와 승인된 두 공급자 키를 설정합니다. 카탈로그 갱신은 완성된 스냅샷을 게시하며 중간 페이지 실패 시 기존 데이터를 보존합니다. 앱의 페이지 요청마다 전체 공급자 목록을 새로 다운로드하지 않습니다.

### 2. Pleos 앱 빌드와 로컬 실행

Android SDK 위치는 각자의 `apps/pleos/local.properties`에 설정합니다. 이 파일과 빌드 캐시는 Git에서 제외됩니다.

```bash
bash apps/pleos/gradlew -p apps/pleos :app:assemblePleosDebug \
  -PcampApiBaseUrl=http://127.0.0.1:8000/api/v1
adb reverse tcp:8000 tcp:8000
adb install -r apps/pleos/app/build/outputs/apk/pleos/debug/app-pleos-debug.apk
adb shell am start -n camp.room.app/camp.room.app.MainActivity
```

공식 Pleos AVD가 필요하며 일반 Android 이미지에는 Vehicle·Navigation·Speech 서비스가 없습니다. `pleosDebug`에만 로컬 개발용 HTTP 예외가 있습니다. 운영/release에는 실제 접근 가능한 HTTPS API 주소를 전달해야 합니다.

```bash
bash apps/pleos/gradlew -p apps/pleos :app:assemblePleosRelease \
  -PcampApiBaseUrl=https://YOUR_BACKEND/api/v1
```

**현재 release 서명 설정이 없으므로 이 명령의 unsigned 결과는 설치 가능한 제출 APK가 아닙니다.** 기존 검증 APK는 debug입니다. 제출용 APK 준비 상태와 경로는 [apk/README.md](apk/README.md)를 확인하세요. 서명키를 새로 만들거나 debug APK를 release로 이름만 바꾸지 않습니다.

### 3. 관리자·worker

관리자 실행·서버 인증과 worker 갱신 설정은 [실행 안내](docs/USAGE.ko.md), [인증](docs/AUTH.ko.md), [외부 데이터](docs/INTEGRATIONS.md)를 참고하세요. 이전 시연 시점의 문서는 당시 상태를 기록하며 현재 구현 범위와 제출 상태는 이 README와 `apk/README.md`를 기준으로 확인합니다.

## 테스트

```bash
.venv/bin/python -m pytest apps/api/tests apps/worker/tests -q
.venv/bin/python apps/api/scripts/export_openapi.py --check
bash apps/pleos/gradlew -p apps/pleos :api-client:test :app:testPleosDebugUnitTest
python3 apps/pleos/verify_contracts.py
```

서버 테스트는 공급자 페이지 실패·중복·필터·카탈로그 세대와 상세 접근을 확인합니다. Android 테스트는 페이지 상태, 목적지 검증, 세션 수명주기, 조회 실패와 음성 입력 진단을 포함합니다. 관리자 검사 명령은 `apps/admin/package.json`을 따릅니다. 테스트와 APK 빌드, 에뮬레이터 화면 검수, 실차 검증은 별개입니다.

## 코드와 문서

| 경로 | 역할 |
| --- | --- |
| `apps/pleos/app` | Compose 화면, Hilt, ViewModel/StateFlow, 실행 환경별 SDK 어댑터 |
| `apps/pleos/api-client` | 서버 OpenAPI에서 생성하는 Kotlin jvm-ktor 클라이언트 |
| `apps/api` | 공급자·검색·여행·준비물 API와 관계형 저장소 |
| `apps/admin` | Next.js 관리자/BFF |
| `apps/worker` | 공유 캐시 갱신 |
| `apk/` | 제출용 signed release APK 안내; debug/unsigned 미포함 |
| `docs/PRD.md`, `docs/FUNCTIONAL_SPEC.md` | 제품 요구사항과 기능 명세 |
| `docs/architecture`, `docs/database` | 서버 구조·ERD·저장소 검토 |

`.env`, API 키, 서명키, SDK 이미지·바이너리, 로컬 DB, 녹화 원본·렌더 중간물·진단 로그는 원격에 포함하지 않습니다. 예시 환경설정에는 실제 credential을 넣지 않습니다. [first-fluke/fullstack-starter](https://github.com/first-fluke/fullstack-starter)를 바탕으로 개발했습니다.
