# Camp Room · 캠프룸

**Kotlin·Jetpack Compose로 구현한 Pleos 차량용 캠핑 앱입니다.** 찾기 / 내 캠핑 / 차박의 세 메뉴가 같은 캠핑장과 일정을 공유하고, 준비물 추천부터 장바구니·수령지·데모 주문까지 연결합니다. 메인 프런트엔드는 실제 Android 앱이며 브라우저 화면으로 대체하지 않습니다.

## 실행 구조

| 경로 | 책임 |
|---|---|
| apps/pleos/app/src/main | Kotlin Compose 화면, 모델, OkHttp 저장소, ViewModel/StateFlow |
| apps/pleos/app/src/demo | Android 에뮬레이터용 명시적 차량/내비 시뮬레이터 연결 |
| apps/pleos/app/src/pleos | 실제 Vehicle2.3.2/NaviHelper3.0.5 SDK 직접 연결 |
| apps/api | FastAPI·SQLite, 캠핑장/날씨 공급자, 여행·커머스 상태 |
| docs/PRD.md / FUNCTIONAL_SPEC.md | 제품 및 기능 요구사항 |
| docs/plans/contracts/camp-room-api.md | 앱·서버 공통 HTTP 계약 |
| docs/product | 사용자 제공 SDK 원본·선언 검토 |
| docs/verification | 실제 수행한 설치·빌드·검증 결과 |

기반은 [first-fluke/fullstack-starter](https://github.com/first-fluke/fullstack-starter)입니다. 불필요한 worker, Terraform infra, Flutter 앱을 제거했습니다. 초기 브라우저 구현 가정은 사용자의 Kotlin 플랫폼 정정으로 폐기했으며 이전 작업은 완료로 꾸미지 않고 partial 기록으로 보존합니다.

## 1. API 실행

Python3.12 이상과 uv가 필요합니다.

```bash
uv sync --all-packages
cp .env.example .env
.venv/bin/python -m uvicorn app.main:app --app-dir apps/api --host 127.0.0.1 --port 8000
```

API 문서: http://127.0.0.1:8000/docs. SQLite 상태는 기본 `apps/api/data/camp-room.sqlite3`에 저장됩니다. 실제 `.env`와 DB는 Git에 포함하지 않습니다. 서버는 단일 가상 시연 계정이며 공개 multi-user 서비스로 배포하지 않습니다.

## 2. Android 앱 빌드·설치

JDK17, Android SDK35/build-tools35가 필요합니다. `ANDROID_HOME` 또는 gitignored `apps/pleos/local.properties`의 sdk.dir을 설정합니다. Gradle8.11.1 wrapper, AGP8.9.2, Kotlin2.1.20, Compose BOM2025.04.01을 사용합니다.

```bash
bash apps/pleos/gradlew -p apps/pleos :app:assembleDemoDebug
adb install -r apps/pleos/app/build/outputs/apk/demo/debug/app-demo-debug.apk
adb shell am start -n camp.room.app.demo/camp.room.app.MainActivity
```

기본 demo API 주소는 `http://10.0.2.2:8000/api/v1`입니다. Android emulator에서 호스트 API로 연결하는 주소입니다. 다른 장치에서는 `-PcampApiBaseUrl=https://YOUR_BACKEND/api/v1`를 빌드 명령에 전달합니다. demo flavor에서만 개발 HTTP cleartext를 허용합니다.

실제 SDK flavor 컴파일/빌드:

```bash
bash apps/pleos/gradlew -p apps/pleos :app:assemblePleosDebug -PcampApiBaseUrl=https://YOUR_BACKEND/api/v1
```

Pleos SDK는 공식 Maven `https://nexus-playground.pleos.ai/repository/maven-releases/`의 `ai.pleos.playground:Vehicle:2.3.2`, `ai.pleos.playground:NaviHelper:3.0.5`를 사용합니다. 차량·내비 호출은 로컬 SDK로 실행하며 백엔드 차량 시뮬레이터를 사용하지 않습니다. 실제 차량 권한·서비스 연결·정차 상태 호스트 계약은 해당 환경에서 검증해야 합니다. APK 빌드 성공은 실차 검증과 다릅니다.

## 직접 사용할 수 있는 여정

1. **찾기**에서 지역·날짜·인원·시설을 정하고 장소 상세의 규칙·가용성 출처를 확인합니다.
2. 장소를 저장하면 **내 캠핑**에 일정·날씨·이동 예상이 모입니다. demo에서는 길안내 상태가 저장되고, pleos에서는 실제 NaviHelper 이벤트를 기다립니다.
3. **차박**에서 앞좌석 독서·뒷좌석 휴식 등 장면과 구역별 설정을 조절합니다. 명령 수락·관측·미지원·실패를 구분합니다.
4. 준비물을 담고 수량을 바꾼 뒤 자택 주소 또는 캠핑장 수령 위치·사이트 참조를 입력합니다.
5. 배송 조건·예정일·전체 금액을 확인하고 **데모 결제**로 주문을 저장합니다. 실제 카드 승인·예약·택배 접수는 발생하지 않습니다.

## 외부 데이터와 토큰

루트 `.env`에서 CAMP_DATA_MODE=demo/auto/live를 선택합니다. GOCAMPING_SERVICE_KEY, KMA_API_HUB_KEY 또는 KMA_SERVICE_KEY는 서버에서만 읽습니다. Android 앱이나 공개 환경변수에 토큰을 넣지 않습니다. 실제 키 발급·공급자 호출 성공 여부는 [INTEGRATIONS.md](docs/INTEGRATIONS.md)와 검증 결과를 따릅니다.

고캠핑 장소 정보로 예약 재고를 만들어내지 않습니다. 실제 응답이 없는 가격·차박 숙박 허용·잔여 자리·예약 수는 확인 필요입니다. 예보 제공 기간 밖은 미제공입니다. 공급자별 실제/데모·출처·갱신 시각을 유지합니다.

## 검증과 영상

```bash
.venv/bin/python -m pytest apps/api/tests/test_camp_room.py -q
python apps/pleos/verify_contracts.py
bash apps/pleos/gradlew -p apps/pleos :app:assembleDemoDebug :app:compilePleosDebugKotlin
python scripts/record-native-demo.py
```

기록 스크립트는 실제 설치한 Android APK를 emulator에서 조작하고 API 결과를 확인하며 native MP4를 녹화합니다. 소스 계약 검사와 Gradle 컴파일, Android emulator 실행, 실제 차량 검증의 범위를 구분합니다. 실제 진행 결과는 docs/verification에 기록합니다.

## OMA

공식 oh-my-agent20.13.0 전체 설치(All33 skills) 후 PRD → 기능명세/API 계약 → native 역할 구현 → Android 통합 QA/녹화로 진행했습니다. 사용자가 지정한 Codex Sol Ultra 리드를 사용하며 실제 역할은 native dispatch 모델 설정을 상속합니다. 외부 CLI 모델을 대신 실행했다고 주장하지 않습니다. 초기 web lineage와 명시적 native scope correction lineage는 각각 보존됩니다.

```bash
npm install --global oh-my-agent@20.13.0
oma install --semantic-memory none
oma doctor --json
```

설치에서 Korean / Codex / Auto / All / Python을 선택합니다. Aside 외부 사이트 계정 작업은 공식 CLI와 인증된 브라우저 프로필이 필요합니다. 실제 계정 선택·사용자 인증이 필요한 상태와 토큰 발급 완료 상태를 구분합니다.
