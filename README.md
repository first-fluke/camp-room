# Camp Room · 캠프룸

캠프룸은 캠핑장을 찾고 이동한 뒤 차 안에서 쉬는 시간을 돕는 **Pleos 차량용 Kotlin·Jetpack Compose 앱**입니다. 찾기, 내 캠핑, 차박의 세 메뉴가 같은 장소와 일정을 공유합니다. 필요한 캠핑용품을 담고 자택 또는 캠핑장으로 받을 준비도 한 앱에서 이어갑니다.

## 문서 안내

| 문서 | 내용 |
| --- | --- |
| [제품 요구사항](docs/PRD.md) | 사용자, 해결할 문제, 제공 범위와 후속 확장 |
| [기능명세서](docs/FUNCTIONAL_SPEC.md) | 화면 동작, 상태, 오류와 인수 기준 |
| [서버 구조도](docs/architecture/SERVER_ARCHITECTURE.md) | 차량 앱·관리자·워커·API·Turso의 Mermaid 구조도 |
| [DB ERD](docs/database/ERD.md) | 32개 테이블의 키와 관계, 영역별 Mermaid ERD |
| [독립 DB 검토](docs/database/SCHEMA_REVIEW.md) | 3NF 검토, 제약조건과 트랜잭션 검증 |
| [실행 안내](docs/USAGE.ko.md) | 앱, API, 관리자, 워커 실행 및 검증 명령 |
| [Pleos 디자인 적용](docs/DESIGN_SYSTEM.md) | 테마·글꼴·공통 컴포넌트와 검증 범위 |
| [새 디자인·앱 실행 검증](docs/verification/pleos-design-runtime.md) | C1 APK·화면·시연·로컬 관측성 검사 결과 |
| [앱 진단과 분석](docs/OBSERVABILITY.md) | 로컬 로그, Sentry·PostHog 설정과 수집 범위 |
| [인증과 비밀값](docs/AUTH.ko.md) | 관리자 세션, 서버 인증, 현재 프로필 범위 |
| [외부 데이터](docs/INTEGRATIONS.md) | 공급자 어댑터, 환경변수, 연결 상태 |
| [SDK 적용표](docs/product/SDK_CAPABILITY_MATRIX.md) | 사용자 제공 명세에 따른 5개 SDK의 역할 |

## 사용 흐름

1. **찾기**에서 지역·날짜·인원·시설을 고르고 캠핑장의 규칙과 예약 가능 정보의 출처를 확인합니다.
2. **내 캠핑**에 장소를 저장하면 일정·날씨·이동 예상이 모입니다. 주변 장소 찾기, 도착 알림, 여행 안내 듣기도 이 흐름에서 사용합니다.
3. **차박**에서 짐 정리, 함께 쉬기, 앞좌석 독서·뒷좌석 휴식, 취침, 출발 준비를 선택합니다. 설정을 적용한 결과와 실제 관측값을 구분해 확인합니다.
4. 준비물을 장바구니에 담고 **자택 또는 캠핑장 수령**을 선택합니다. 수령 조건과 도착 예정일을 확인한 뒤 데모 주문을 저장합니다.

한국어·영어 UI와 시스템 언어 따르기를 지원합니다. 날짜·금액 표시와 음성 언어도 선택한 언어를 반영합니다. 공급자가 보낸 장소명·주소·원문 설명은 원래 언어로 보존합니다.

## 코드 구성

| 경로 | 역할 |
| --- | --- |
| `apps/pleos/app` | Compose 화면, Hilt 의존성 주입, ViewModel/StateFlow, 실행 환경별 SDK 연결 |
| `apps/pleos/api-client` | OpenAPI로 생성한 Kotlin `jvm-ktor` 클라이언트와 DTO, 직렬화 검증 |
| `apps/api` | Python 3.14·FastAPI, 공급자 어댑터, 여행·상품·주문 API, 관계형 저장소 |
| `apps/admin` | Next.js 운영 화면과 서버 BFF, 공급자·수령 조건·데모 주문 관리 |
| `apps/worker` | 캠핑장·날씨 캐시를 갱신하는 Python 3.14 배치 실행기 |
| `docs/product` | 제공받은 SDK JSONL, 선언 검토, 공모전 신청서 |

[first-fluke/fullstack-starter](https://github.com/first-fluke/fullstack-starter)를 바탕으로 만들었습니다. Flutter·Terraform 앱은 제외했고 캐시 갱신에 필요한 워커는 복구했습니다.

앱은 FastAPI의 `apps/api/openapi.json`에서 생성한 클라이언트를 사용합니다. OpenAPI Generator 7.26.0의 `kotlin`/`jvm-ktor`, Ktor 3.1.3, kotlinx.serialization 1.8.1을 사용하며 생성 파일을 직접 수정하지 않습니다. Hilt 2.56.2가 API, 저장소, ViewModel과 실행 환경별 SDK 어댑터를 연결합니다.

| Pleos SDK | 버전 | 앱에서 맡는 일 |
| --- | --- | --- |
| Vehicle | 2.3.2 | 차량 상태, 지원 범위 조회, 차박 장면과 제어 |
| NaviHelper | 3.0.5 | 예상 이동 정보, 목적지, 길안내 상태 |
| FusedLocation | 1.1.3 | 최근 위치, 주변 장소 정렬, 캠핑장 진입·이탈 알림 |
| TextToSpeech | 3.3.1 | 여행 안내 읽기 |
| SpeechToText | 3.3.1 | 인식한 문장을 명령 미리보기로 전달 |

`pleos` 실행 환경은 이 SDK들을 차량 안에서 직접 호출합니다. `demo`는 같은 Android 화면에서 차량·내비 시뮬레이터와 기기의 위치·음성 서비스를 사용합니다.

## 빠른 실행

Python 3.14, uv, JDK 17, Android SDK 35가 필요합니다. 관리자 화면에는 Node.js 24를 사용합니다. 처음 환경을 준비할 때만 `.env.example`을 `.env`로 복사하고 서버 설정을 채웁니다. 기존 `.env`는 덮어쓰지 않습니다.

```bash
uv sync --all-packages --frozen
.venv/bin/python -m uvicorn app.main:app --app-dir apps/api --host 127.0.0.1 --port 8000
```

기본 데이터 모드는 `demo`입니다. Turso URL·토큰이 있으면 Turso를 사용하고 `CAMP_DB_PATH`를 지정하면 독립된 로컬 SQLite 파일을 사용합니다. 두 저장소는 같은 32개 STRICT 테이블과 제약조건을 적용합니다. 설정 항목은 [외부 데이터 문서](docs/INTEGRATIONS.md)를 참고합니다.

별도 터미널에서 앱을 빌드하고 에뮬레이터에 설치합니다.

```bash
bash apps/pleos/gradlew -p apps/pleos :app:assembleDemoDebug
adb install -r apps/pleos/app/build/outputs/apk/demo/debug/app-demo-debug.apk
adb shell am start -n camp.room.app.demo/camp.room.app.MainActivity
```

에뮬레이터 기본 API 주소는 `http://10.0.2.2:8000/api/v1`입니다. 실제 기기용 빌드에서는 `-PcampApiBaseUrl=https://YOUR_BACKEND/api/v1`로 서버 주소를 지정합니다. `pleos` 빌드는 `:app:assemblePleosDebug`를 사용합니다.

## 검증과 시연

최신 한국어 UI의 변경 내용과 검수 범위는 [한국어 UI 검수 기록](docs/verification/korean-ui-copy-review.md)에서 확인할 수 있으며 APK와 시연 영상은 [데모 3 릴리스](https://github.com/first-fluke/camp-room/releases/tag/v1.0.0-demo.3)에서 제공합니다.

배포용 Demo APK는 API 서버를 `127.0.0.1:8000`으로 찾으므로 설치 후 로컬 API 서버를 실행하고 `adb reverse tcp:8000 tcp:8000`을 설정해야 합니다.

```bash
.venv/bin/python -m pytest apps/api/tests apps/worker/tests -q
.venv/bin/python scripts/review-database.py
.venv/bin/python apps/api/scripts/export_openapi.py --check
bash apps/pleos/gradlew -p apps/pleos :api-client:test :app:assembleDemoDebug :app:assemblePleosDebug
```

실제 앱 녹화는 API와 에뮬레이터를 실행하고 APK를 설치한 상태에서 `python scripts/record-native-demo.py`로 진행합니다. 앱 입력과 서버 상태를 확인하면서 Android 화면을 녹화합니다. 소스 계약 검사, APK 빌드, 에뮬레이터 동작, 실차 검증은 각각 수행한 범위를 기록합니다.

현재 외부 캠핑장·날씨 키는 미설정이며 실시간 예약·결제·배송 사업자도 연결하지 않았습니다. 주문은 실제 결제가 없는 데모 주문입니다. Turso 저장과 원격 트랜잭션은 실제 DB에서 검증했습니다. 자세한 검토와 한계는 [독립 DB 검토](docs/database/SCHEMA_REVIEW.md)와 [SDK 적용표](docs/product/SDK_CAPABILITY_MATRIX.md)에 있습니다.

## 개발 방식

oh-my-agent 20.13.0의 33개 스킬을 설치하고 PRD·기능명세·API 계약을 기준으로 병렬 구현했습니다. 한국어 문서는 사용자가 지정한 [im-not-ai](https://github.com/epoko77-ai/im-not-ai)의 `humanize-korean`으로 검수합니다. 실제 `.env`, DB 파일, 빌드 결과와 실행 중간 기록은 Git에 넣지 않습니다.
