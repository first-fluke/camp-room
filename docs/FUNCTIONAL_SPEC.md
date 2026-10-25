# Camp Room 기능명세서

버전 1.2 · 2026-10-25. PRD의 동작 계약이며 HTTP 세부 형식은 `plans/contracts/camp-room-api.md`를 따른다. 명시적인 `CAMP_DATA_MODE=demo` 시연은 데모 데이터를 사용한다. `auto`와 `live`에서는 공급자별 모드·출처·오류 상태를 구분한다.

## 1. 공통 상태와 경계

메인 앱은 Kotlin·Jetpack Compose Material3, API는 Python 3.14·FastAPI다. `apps/api/openapi.json`에서 생성한 Kotlin `jvm-ktor` 클라이언트와 DTO를 사용한다. Hilt가 API·저장소·ViewModel·실행 환경별 SDK 어댑터를 연결하고 StateFlow로 화면 상태를 관리한다.

Android `demo`의 `BuildConfig.API_BASE_URL`은 `http://10.0.2.2:8000/api/v1`이며 Gradle `-PcampApiBaseUrl`로 바꾼다. `pleos`의 차량·내비·위치·음성 요청은 로컬 `PlatformBridge`로 전달한다. API 저장소는 Turso 또는 독립된 로컬 SQLite이며 같은 32개 STRICT 테이블을 사용한다. 현재 서버 선택형 데모 프로필과 관리자 인증을 제공하며 일반 사용자 로그인은 후속 범위다.

`GET /bootstrap`은 초기 campsites, trip, weather, vehicle, products, cart, orders와 mode를 반환한다. UI는 최초 조회 중 skeleton, 실패 시 재시도를 제공한다. 이후 동작의 API 응답을 기준으로 화면 상태를 갱신한다. 날짜는 ISO `YYYY-MM-DD`, 타임스탬프는 timezone이 포함된 ISO 문자열, 금액은 KRW 정수다. 체크아웃은 숙박 기간에서 제외된다.

## 2. 화면 및 동작

### FIND — 찾기

- 상단에 여행 날짜·인원과 검색 영역을 두고 지역·차박·시설 chip을 제공한다.
- 카드에는 사진/일러스트, 이름, 지역, 1박 요금, 시설, 가용성, 출처 및 갱신 시각을 보여준다.
- 필터는 AND 조건이다. 결과 0개이면 조건 초기화 버튼을 제공한다.
- 카드를 선택하면 상세 패널/화면에 장소 설명, 규칙, 입퇴실, 차박 허용, 캠핑용 전기/EV 충전, 배송 수령 조건을 표시한다.
- 날짜 변경은 전체 숙박 기간으로 가용성을 다시 조회한다. 공급자 미지원은 확인 필요, 과거 스냅샷은 갱신 필요로 표현한다.
- `내 캠핑에 담기`는 `PUT /trip`을 호출한다. 성공 후 여행 전체가 갱신된다. 선택한 카드와 내 캠핑 목적지는 일치해야 한다.

### TRIP — 내 캠핑

- 저장된 장소, 일정·인원, 입퇴실, 날씨 카드와 밤사이 기온/비 가능성을 보여준다.
- 센서의 현재 실내 온도와 외부 예보를 명확하게 분리한다.
- 경로 미리보기는 거리·시간·예상 소비량·도착 SOC와 충전 경유 여부를 보여준다. 예상 수치는 데모 시뮬레이션임을 표시한다.
- `demo`의 길안내 시작은 `POST /navigation/start`로 시뮬레이터 상태를 저장한다. `pleos`는 NaviHelper를 호출하고 실제 `ROUTE_DRIVE` 상태를 받은 뒤 길안내 시작을 표시한다.
- 규칙·체크리스트와 준비물 추천을 제공한다. 장바구니 버튼은 어디서나 접근 가능하다.
- 여행이 없으면 찾기로 이어지는 빈 상태를 제공한다.

### CABIN — 차박

- 차량 상단에 SOC·실내 온도·통신 시각·문·창문 상태와 연결 출처를 표시한다. `demo`에서는 시뮬레이터 연결임을 표시한다.
- 차량 실루엣이나 평면도에 앞좌석/뒷좌석 구역을 표시하고 현재 적용한 장면을 눈에 보이게 반영한다.
- 장면 chip은 짐 정리, 함께 쉬기, 앞좌석 독서·뒷좌석 휴식, 취침 준비, 출발 준비다.
- `demo`의 `POST /vehicle/scenes`는 sceneId를 입력받아 항목별 명령 결과를 반환한다. 사용자 조작 없이 날씨 때문에 자동으로 조작하지 않는다.
- `demo`의 `POST /vehicle/controls`는 지원 범위 내 온도·조명·차광을 조절한다. 결과는 command status와 observed value를 구분한다.
- 장면 적용 중 중복 실행을 막고 완료 후 readback을 표시한다. 부분 실패는 해당 항목만 재시도할 수 있다. 데모 실패 시나리오를 위한 명시적 테스트 입력을 허용하되 기본 UI를 방해하지 않는다.
- 알 수 없는 motionState를 정차로 바꾸지 않는다. 네이티브에서 호스트 정차 신호가 없으면 좌석 이동 등 stationarity-sensitive 제어를 차단한다.
- 출발 준비는 문·창문·SOC·다음 목적지를 확인하며 관측되지 않은 값은 확인 필요로 둔다.

### COMMERCE — 준비물/장바구니/주문

- 상품에는 이름·카테고리·가격·재고·추천 이유와 표시 이미지가 있다.
- 담기·수량 변경·삭제는 서버에 반영한다. 0 이하 수량, 없는 상품, 재고 초과는 오류다.
- 장바구니는 상품별 금액·배송료·합계를 표시한다. 비어 있으면 추천으로 돌아갈 수 있다.
- 견적은 배송지 종류 `home|campsite`, 여행 장소·날짜를 검증한다. 캠핑장 수령 불가와 ETA 불일치를 `eligible:false`, 명확한 이유로 반환한다.
- 주문 전 확인 화면에 수령 조건·배송 예정일·전체 금액과 `데모 결제`를 표시한다.
- 주문 요청은 idempotencyKey가 필수다. 동일 key·동일 요청은 동일 주문, 동일 key·다른 요청은 conflict다. 재고·가격·견적은 서버가 재계산한다.
- 성공 주문에는 `DEMO-` 접두어 번호, `demo_confirmed` 상태, 주문시각, 항목, 금액, 배송지와 ETA가 포함된다. 실제 결제 승인번호나 택배 접수로 표현하지 않는다.
- 주문 성공 후 장바구니를 비우고 주문내역이 새로 고침 후 유지되어야 한다.

## 3. 데이터 모델

| 모델 | 핵심 필드 |
|---|---|
| Campsite | id,name,region,address,latitude,longitude,description,imageUrl,nightlyPrice,facilities,carCampingAllowed,evChargingAllowed,rules,checkInTime,checkOutTime,delivery,availability,source |
| Availability | status,remaining nullable,coverage,checkedAt,provider,demo |
| Trip | campsiteId,checkIn,checkOut,guests,navigationStarted,updatedAt |
| Weather | source,updatedAt,forecast[],overnightLow,rainProbability,summary,demo |
| Vehicle | mode,motionState,soc,cabinTemperature,targetTemperature,front/rear light/blind state,doors,windows,updatedAt,lastSceneId |
| CommandResult | control,target,status,observed,confirmation,message |
| Product | id,name,price,stock,category,recommendationReason,imageUrl |
| Cart | items[{productId,quantity,unitPrice,lineTotal}],subtotal,shipping,total |
| Quote | eligible,reason,destination,eta,receivingConditions,subtotal,shipping,total |
| Order | id,status,mode,createdAt,items,total,destination,eta,receivingConditions |
| CheckoutInput | idempotencyKey,destination,recipientName,address,campsiteId,checkIn,checkOut,siteReference |

공급자 실제 예약 건수/분모가 없으면 bookedCount와 occupancyRate를 응답하지 않는다. 센서 미관측 값은 null이다. unknown은 거짓·0·정차로 변환하지 않는다.

## 4. 오류와 보안

유효성 오류는 422 또는 400, 미존재 404, 재고·idempotency 충돌은 409, 외부 미연결은 명시적 unavailable로 처리한다. 화면은 알려진 인증·입력·통신 오류를 선택한 언어로 표시하고 공급자 원문 진단은 출처와 함께 보존한다. API는 가격·재고·가용성·배송 조건을 신뢰 경계 안에서 검증한다. 사용자 입력을 HTML로 직접 삽입하지 않는다. 시연 주소·수령인은 가상 데이터다.

## 5. 네이티브 Pleos 계약

Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3, TextToSpeech 3.3.1, SpeechToText 3.3.1의 제공 선언은 `product/pleos-sdk.spec.jsonl`이 유일한 SDK 기준이다. Kotlin 앱은 `PlatformBridge`와 실행 환경별 Vehicle·Navi·Assist 어댑터로 SDK를 감싼다. 화면 상태는 요청 목표와 관측값을 분리한다. 리스너는 수명주기에 맞춰 등록·해제하고 복귀 시 현재 값 조회 후 재구독한다. 선언 검토, APK 빌드와 실차 동작 확인은 별도의 검증 결과로 기록한다.

정확한 매핑 예: SOC `getEvBatteryStateOfCharge`/register; HVAC 목표 `getHvacTemperature`/`setHvacTemperature`, 센서 `getHvacTemperatureCurrent`; 읽기등 `getCurrentReadingLightState`/`registerReadingLightState`/`setReadingLightSwitch`; 앰비언트 `setAmbientLightBrightness`/`setAmbientLightBrightnessColor`(0xFF alpha ARGB); 창문 `getCurrentWindowPosition`; 스마트글라스 `getCurrentSmartGlassLevel`. 좌석 메모리 `checkSeatMemoryCapabilities`와 `selectSeatMemory(area,value,onFailed)`를 사용하되 성공 관측을 만들어내지 않는다. 충전 목표 잔량은 setter만으로 readback 완료를 표시하지 않는다.

## 6. 검증 시나리오

1. 지역+시설+차박 필터와 빈 결과 복구, 날짜 역전 및 인원 범위 오류.
2. 장소 저장 후 메뉴 전환·새로 고침·API 재시작에서 여행 일관성.
3. 가용성 unknown/오래된 스냅샷과 실제 예약 수 미표시.
4. 장면 적용 성공·일부 실패·미지원·미관측 항목, 관측값과 목표값 분리.
5. 장바구니 금액·수량·재고 제한, 자택 및 캠핑장 배송 견적.
6. 수령 불가·일정 불일치 주문 차단, 주문 중복 요청 동일성, 주문 저장.
7. Android emulator 가로 화면과 작은 화면에서 탐색·상세·주문 조작, 키보드 접근.
8. Android emulator 녹화에 찾기 → 내 캠핑 → 길안내 → 차박 → 장바구니 → 데모 주문의 입력과 결과 포함.

## 7. 실행 및 제출

개발 실행은 실제 Android demo debug APK와 FastAPI 서버를 사용한다. Gradle wrapper8.11.1, AGP8.9.2, Kotlin2.1.20, JDK17, compileSdk/targetSdk35, Compose BOM2025.04.01을 사용한다. main/demo/pleos sourceSet을 분리한다. 재현 절차는 README에 기록하고 의존성·환경변수 예시를 제공한다. 결과에는 소스, PRD, 본 명세, API 계약, OMA 설치/에이전트 실행 증거, 테스트 결과와 실제 시연 MP4를 포함한다. 실제 외부 연동 및 APK 검증 여부를 구분해 보고한다.

## 8. 네이티브 플랫폼 분리와 생명주기

common main의 PlatformBridge 호출은 sourceSet마다 구현을 선택한다. demo는 API 시뮬레이터의 차량/내비 endpoint를 사용하고 pleos는 Vehicle/NaviHelper를 직접 호출한다. bootstrap의 서버 vehicle은 pleos flavor에서 로컬 SDK snapshot으로 교체한다. 없는 센서/구역 값은 null이며 readingLight를 false, 밝기를0으로 바꾸지 않는다. 실제 내비는 onTripInfo/onNavigationStatus.ROUTE_DRIVE 이벤트를 관측한 뒤에만 결과를 표시한다. TripInfo energyConsumption Wh는 UI kWh로 변환한다. 정차 호스트 신호가 UNKNOWN이면 차량 설정을 차단한다. 리스너·요청은 foreground lifecycle에 맞춰 시작/중단하고 종료 시 release한다.

## 9. 위치와 음성

찾기의 내 주변 버튼은 권한을 확인하고 최근 관측 위치 기준으로 장소를 정렬한다. 내 캠핑은 캠핑장 500m 도착/이탈 알림의 등록과 실제 감지를 구분하고 여행 안내 듣기로 실제 TTS 재생 시작을 확인한다. 음성 명령 dialog는 STT 최종 결과 또는 명확하게 구분한 직접 입력을 받으며 장면/길안내 화면의 미리보기까지만 연다. 실제 제어·길안내·주문을 자동 실행하지 않는다. FusedLocation/TTS/STT 권한·서비스 실패가 다른 캠핑장/커머스 기능을 막지 않도록 모듈을 분리한다. 정확한 계약과 주의점은 [SDK 적용표](product/SDK_CAPABILITY_MATRIX.md)를 따른다.

## 10. 운영용 웹과 원격 저장소

메인 사용자 앱은 Android이며 apps/admin은 별도의 운영자 Next 웹이다. 인증된 관리자만 공급자·DB 상태 조회, 캠핑장 택배 수령 조건 변경, 데모 주문 처리상태/운영 메모를 변경할 수 있다. 실제 결제승인/택배접수와 구분한다. API 저장소는 local SQLite 또는 사용자가 제공한 Turso를 사용하며 서버 환경변수로 선택한다. Turso와 admin 비밀은 Android/브라우저 번들로 전달하지 않는다.

## 11. 다국어

앱과 관리자 화면은 한국어·영어를 지원한다. 앱 언어 설정은 시스템 언어, 한국어, 영어이며 날짜·금액 형식과 STT/TTS 언어에 반영한다. 내부 ID와 API enum 값은 언어에 따라 바꾸지 않는다. 공급자의 장소명·주소·원문 설명은 보존한다. 일부 SDK 진단은 원래 언어로 표시될 수 있다.

## 12. 관계형 저장과 워커

캠핑장 시설·규칙, 장바구니·주문 항목과 예보는 별도의 행으로 저장한다. 날짜별 가용성은 캠핑장·체류 기간·인원·사이트 종류를 함께 키로 사용한다. 주문 당시 상품명·가격·배송지는 이력으로 보존하며 현재 카탈로그 수정으로 바꾸지 않는다. 상세 관계는 [ERD](database/ERD.md), 정규화 검토는 [독립 DB 검토](database/SCHEMA_REVIEW.md)를 따른다.

주문은 재고·주문·항목·배송·멱등 키와 장바구니 변경을 하나의 트랜잭션에서 처리한다. 서버가 결과를 확인하지 못한 주문을 다시 보낼 때도 같은 멱등 키를 사용한다.

워커는 `POST /api/v1/admin/providers/refresh`를 호출한다. API는 120초 임대의 소유자를 확인한 뒤 캠핑장 600초·날씨 300초 캐시를 갱신한다. 한 실행에서 현재 여행과 최대 두 곳의 대안 캠핑장을 조회한다. 실패 상태와 마지막 성공 캐시는 보존한다. 구조와 갱신 순서는 [서버 구조도](architecture/SERVER_ARCHITECTURE.md)에 있다.

## 13. 앱 진단과 분석

화면·작업·API·SDK 결과를 정해진 이벤트와 속성으로 기록한다. 로컬 로그는 앱 전용 파일 두 개를 각각 128KiB까지 보관한다. 외부 전송은 기본으로 꺼져 있으며 Sentry·PostHog 프로젝트 설정이 있어야 활성화된다. 원문 위치·음성·주소·주문·요청 본문은 수집하지 않는다. 수집 정책과 설정은 [앱 진단과 분석](OBSERVABILITY.md)에 있다.

## 14. 차량 화면 디자인

Pleos 공개 가이드를 참고한 Compose 테마에서 Asta Sans와 주간·야간 팔레트를 사용한다. 공통 버튼은 눌림·비활성·처리 중 상태와 햅틱 피드백을 제공한다. 보조 글자는 최소 12sp, 주요 동작은 18sp이며 짧은 텍스트는 최대 3줄로 표시한다. 크기·대비 검사와 실제 디스플레이 검증의 범위는 [디자인 적용 문서](DESIGN_SYSTEM.md)를 따른다.
