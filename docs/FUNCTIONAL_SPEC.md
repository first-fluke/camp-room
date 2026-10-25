# Camp Room 기능명세서

버전 1.1 · 2026-10-25. PRD의 동작 계약이며 HTTP 세부 형식은 `plans/contracts/camp-room-api.md`를 따른다. 모든 목록·차량·날씨·예약·주문은 Android demo flavor 시연에서 `mode: demo`다.

## 1. 공통 상태와 경계

메인 앱은 Kotlin/Jetpack Compose Material3, API는 FastAPI다. Android demo flavor의 BuildConfig.API_BASE_URL은 `http://10.0.2.2:8000/api/v1`이며 Gradle `-PcampApiBaseUrl`로 바꿀 수 있다. OkHttp와 수명주기 기반 ViewModel/StateFlow로 HTTP 요청과 화면 상태를 관리한다. pleos flavor의 차량·내비 요청은 로컬 SDK PlatformBridge로 전달하며 백엔드 시뮬레이터를 호출하지 않는다. API는 프로세스 재시작 후 유지되는 SQLite 또는 원자적 JSON 저장소를 사용한다. 외부 비밀키 없이 기본 시연이 실행된다. 사용자별 인증은 이번 단일 시연 계정 범위 밖이다. API는 loopback 개발 서버로 실행한다.

`GET /bootstrap`은 초기 campsites, trip, weather, vehicle, products, cart, orders와 mode를 반환한다. UI는 최초 조회 중 skeleton, 실패 시 재시도를 제공한다. 이후 동작의 API 응답을 기준으로 화면 상태를 갱신한다. 날짜는 ISO `YYYY-MM-DD`, 타임스탬프는 timezone을 가진 ISO 문자열, 금액은 KRW 정수다. 체크아웃은 숙박 기간에서 제외된다.

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
- `길안내 시작`은 `POST /navigation/start`로 intent를 저장하고 시작됨 상태를 보여준다. 실제 네이티브 이동은 호스트 어댑터 구현으로 구분한다.
- 규칙·체크리스트와 준비물 추천을 제공한다. 장바구니 버튼은 어디서나 접근 가능하다.
- 여행이 없으면 찾기로 이어지는 빈 상태를 제공한다.

### CABIN — 차박

- 차량 상단 상태에는 SOC·실내 온도·통신 시각·문·창문 상태와 `시뮬레이터 연결`을 보여준다.
- 차량 실루엣이나 평면도에 앞좌석/뒷좌석 구역을 표시하고 현재 적용한 장면을 눈에 보이게 반영한다.
- 장면 chip은 짐 정리, 함께 쉬기, 앞좌석 독서·뒷좌석 휴식, 취침 준비, 출발 준비다.
- `POST /vehicle/scenes`는 sceneId를 입력받아 항목별 명령 결과를 반환한다. 사용자 조작 없이 날씨 때문에 자동으로 조작하지 않는다.
- `POST /vehicle/controls`는 지원 범위 내 온도·조명·차광을 조절한다. 결과는 command status와 observed value를 구분한다.
- 장면 적용 중 중복 실행을 막고 완료 후 readback을 표시한다. 부분 실패는 해당 항목만 재시도할 수 있다. 데모 실패 시나리오를 위한 명시적 테스트 입력을 허용하되 기본 UI를 방해하지 않는다.
- 알 수 없는 motionState를 정차로 바꾸지 않는다. 네이티브에서 호스트 정차 신호가 없으면 좌석 이동 등 stationarity-sensitive 제어를 차단한다.
- 출발 준비는 문·창문·SOC·다음 목적지를 확인하며 관측되지 않은 값은 확인 필요로 둔다.

### COMMERCE — 준비물/장바구니/주문

- 상품은 이름·카테고리·가격·재고·추천 이유와 표시 이미지를 가진다.
- 담기·수량 변경·삭제는 서버에 반영한다. 0 이하 수량, 없는 상품, 재고 초과는 오류다.
- 장바구니는 상품별 금액·배송료·합계를 표시한다. 비어 있으면 추천으로 돌아갈 수 있다.
- 견적은 배송지 종류 `home|campsite`, 여행 장소·날짜를 검증한다. 캠핑장 수령 불가와 ETA 불일치를 `eligible:false`, 명확한 이유로 반환한다.
- 주문 전 확인 화면에 수령 조건·배송 예정일·전체 금액과 `데모 결제`를 표시한다.
- 주문 요청은 idempotencyKey가 필수다. 동일 key·동일 요청은 동일 주문, 동일 key·다른 요청은 conflict다. 재고·가격·견적은 서버가 재계산한다.
- 성공 주문은 `DEMO-` 접두어 번호, `demo_confirmed` 상태, 주문시각, 항목, 금액, 배송지와 ETA를 가진다. 실제 결제 승인번호나 택배 접수로 표현하지 않는다.
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
| Order | id,status,mode,createdAt,items,total,destination,eta,idempotencyKey |

공급자 실제 예약 건수/분모가 없으면 bookedCount와 occupancyRate를 응답하지 않는다. 센서 미관측 값은 null이다. unknown은 거짓·0·정차로 변환하지 않는다.

## 4. 오류와 보안

유효성 오류는 422 또는 400, 미존재 404, 재고·idempotency 충돌은 409, 외부 미연결은 명시적 unavailable로 처리한다. 응답의 `detail`은 한국어 사용자 메시지로 연결 가능해야 한다. API는 가격·재고·가용성·배송 조건을 신뢰 경계 안에서 검증한다. 사용자 입력을 HTML로 직접 삽입하지 않는다. 시연 주소·수령인은 가상 데이터다.

## 5. 네이티브 Pleos 계약

Vehicle 2.3.2와 NaviHelper 3.0.5의 실제 제공 선언은 `product/pleos-sdk.spec.jsonl`이 유일한 SDK 기준이다. Kotlin 호스트는 도메인 `VehiclePort`/`NavigationPort`로 감싼다. 화면 상태는 요청 목표와 관측값을 분리한다. 리스너는 수명주기에 맞춰 등록·해제하고 복귀 시 현재 값 조회 후 재구독한다. 미확인 네이티브 빌드 환경에서 컴파일 성공이나 실차 검증을 주장하지 않는다.

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

common main의 PlatformBridge 호출은 sourceSet마다 구현을 선택한다. demo는 API 시뮬레이터의 차량/내비 endpoint를 사용하고 pleos는 Vehicle/NaviHelper를 직접 호출한다. bootstrap의 서버 vehicle은 pleos flavor에서 로컬 SDK snapshot으로 교체한다. 없는 센서/구역 값은 null이며 readingLight를 false, 밝기를0으로 바꾸지 않는다. 실제 내비는 onTripInfo/onRouteStarted 이벤트를 관측한 뒤에만 결과를 표시한다. TripInfo energyConsumption Wh는 UI kWh로 변환한다. 정차 호스트 신호가 UNKNOWN이면 차량 설정을 차단한다. 리스너·요청은 foreground lifecycle에 맞춰 시작/중단하고 종료 시 release한다.
