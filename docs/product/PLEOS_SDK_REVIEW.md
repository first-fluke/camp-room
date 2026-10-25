# Camp Room 구현 계약·인수 기준 검토

작성 기준: 2026-10-25. 사용자 제공 [SDK JSONL](pleos-sdk.spec.jsonl)의 Vehicle 2.3.2와 NaviHelper 3.0.5 선언을 검토한 문서다. 위치·음성을 포함한 5개 SDK의 적용 범위는 [전체 SDK 적용표](SDK_CAPABILITY_MATRIX.md)를 따른다. 선언 검토와 실행 검증 결과는 구분한다.

## 1. 구현 경계

- Android `demo`는 차량·내비 시뮬레이터와 캠핑장·예약·날씨·상거래 데모 데이터를 사용한다. 화면에 시연 데이터와 연결 출처를 표시한다.
- 실제 차량 연동은 Android Kotlin 앱의 `PleosVehicleAdapter`, `PleosNavigationAdapter`, `PleosAssistAdapter`가 맡는다. `PlatformBridge`는 앱의 경계이며 SDK 자체가 제공하는 규약은 아니다.
- SDK 자체 콜백에 앱의 `commandId`, `receivedAt`, `generation` 등이 있다고 주장하지 않는다. 이 값은 브리지 또는 앱이 추가하는 상태 관리용 메타데이터다.
- 초기화 후 기능·영역·접근 범위를 조회한다. `CapabilityConfig`의 필드는 `accessType`; 수치 범위는 별도의 영역별 `AreaBounds` 또는 선택값 목록이다.
- 지원하지 않는 기능, 조회 중, 조회 실패, 값 없음(null)은 서로 구분한다. null을 0, false, 매진, 문 닫힘으로 바꾸지 않는다.

## 2. 핵심 기능과 SDK 이름

| 기능 | 명령/요청 | 상태 확인 및 구독 | 구현 주의 |
|---|---|---|---|
| 차량 연결 | `Vehicle(context).initialize()` | 각 조회 실패/리스너 실패 처리 | 종료 시 리스너 해제와 `release()` |
| 배터리 잔량 | 읽기 기능 | `getEvBatteryStateOfCharge`, `registerEvBatteryStateOfCharge` | `Int?`, %; 목표 충전 제한과 구분 |
| 공조 목표 온도 | `setHvacTemperature` | `getHvacTemperature`, `registerHvacTemperature` | `SeatArea`, `Float`, `TemperatureUnit` |
| 실제 실내 온도 | 읽기 기능 | `getHvacTemperatureCurrent`, `registerHvacTemperatureCurrent` | 목표 온도와 다른 값 |
| 공조 전원·풍량 | `setHvacPower`, `setHvacFanSpeed` | `getHvacPowerState`, `getHvacFanSpeed` 등 | 풍량 범위를 차량 capability에서 읽음 |
| 독서등 | `setReadingLightSwitch` | `getReadingLightSwitch`, `getCurrentReadingLightState`, `registerReadingLightState` | 명령은 `LightSwitch`, 물리 상태는 `LightState` |
| 앰비언트 밝기 | `setAmbientLightBrightness` | `getAmbientLightBrightness`, `registerAmbientLightBrightnessListener` | 공통 0–100 범위로 단정하지 않음 |
| 앰비언트 색상 | `setAmbientLightBrightnessColor` | `getAmbientLightBrightnessColor`, `registerAmbientLightBrightnessColorListener` | 이름에 Brightness가 있지만 색상 API; ARGB alpha=0xFF |
| 창문 위치 | `setWindowPosition` | `getCurrentWindowPosition`, `registerWindowPosition` | 정수의 단위와 범위는 차량별 capability |
| 스마트글라스 단계 | `setSmartGlassLevel` | `getCurrentSmartGlassLevel`, `registerSmartGlassLevel` | `getSmartGlassConfig`와 기능별 capability 별도 확인 |
| 블라인드 이동 | `moveWindowBlind` | `getCurrentWindowBlindPosition`, `registerWindowBlindPosition` | `MoveControl` 명령; 절대 ‘80% 닫힘’ setter 아님 |
| 좌석 메모리 | `selectSeatMemory` | 지원되는 좌석 위치 getter/리스너 | 성공 콜백이 없음; 임의 ‘좌석 조절 완료’ 금지 |
| 예상 주행 | `requestTrip(TripRequest)` | `onTripInfo(TripInfo)` | 길안내 시작과 구분 |
| 길안내 | `requestRoute(RouteInfo)` | `NaviHelperEventListener`의 실제 수신 상태 | 요청 즉시 ‘길안내 중’ 처리하지 않음 |
| 경유지 포함 길안내 | `requestRouteWithWaypoints` | 동일 | 최대 2개, FIRST/SECOND |
| 충전 시작·중지 | `setEvChargeSwitch` | 지원 상태 조회/리스너 | 충전구 개폐와 별개 |
| 충전 목표 | `setEvChargePercentLimit` | 명령 콜백 값 | 제공 선언에 `getEvChargePercentLimit` 없음 |
| 충전 전류 제한 | `setEvChargeCurrentDrawLimit` | `getEvChargeCurrentDrawLimit` | 단위 A, 실제 소비전력 아님 |

## 3. 정확한 Kotlin 계약

아래는 구현에 자주 필요한 선언의 인자·타입을 그대로 옮긴 것이다. `@RequiresPermission`과 패키지는 원본 선언으로 확인한다.

```kotlin
// ai.pleos.playground.vehicle.api.HVAC
abstract fun setHvacTemperature(
    area: SeatArea, value: Float, unit: TemperatureUnit,
    onComplete: (SeatArea, Float?) -> Unit = { _, _ -> },
    onFailed: (Exception) -> Unit = {}
)
abstract fun getHvacTemperature(
    area: SeatArea, unit: TemperatureUnit,
    onComplete: (Float?) -> Unit, onFailed: (Exception) -> Unit
)
abstract fun checkHvacTemperatureCapabilities(
    onComplete: (CapabilityConfig, Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?) -> Unit,
    onFailed: (Exception) -> Unit
)

// ai.pleos.playground.vehicle.api.Light
abstract fun setReadingLightSwitch(
    area: SeatArea, value: LightSwitch,
    onComplete: (LightSwitch?) -> Unit = {}, onFailed: (Exception) -> Unit = {}
)
abstract fun setAmbientLightBrightness(
    area: SeatArea, value: Int,
    onComplete: (SeatArea, Int) -> Unit, onFailed: (Exception) -> Unit
)
abstract fun setAmbientLightBrightnessColor(
    area: SeatArea, value: Int,
    onComplete: (SeatArea, Int) -> Unit, onFailed: (Exception) -> Unit
)

// ai.pleos.playground.vehicle.api.Window
abstract fun setWindowPosition(
    area: WindowArea, value: Int,
    onComplete: (WindowArea, Int?) -> Unit = { _, _ -> }, onFailed: (Exception) -> Unit = {}
)
abstract fun moveWindowBlind(
    area: WindowArea, value: MoveControl,
    onComplete: (WindowArea, MoveControl?) -> Unit, onFailed: (Exception) -> Unit
)
abstract fun setSmartGlassLevel(
    area: WindowArea, value: Int,
    onComplete: (WindowArea, Int?) -> Unit = { _, _ -> }, onFailed: (Exception) -> Unit = {}
)

// ai.pleos.playground.vehicle.api.Seat
abstract fun selectSeatMemory(
    area: SeatArea, value: Int, onFailed: (Exception) -> Unit = {}
)

// ai.pleos.playground.navi.helper.NaviHelper
fun initialize(onConnected: () -> Unit, onFailed: (Throwable) -> Unit)
fun addListener(listener: NaviHelperEventListener)
fun removeListener(listener: NaviHelperEventListener)
fun requestTrip(tripRequest: TripRequest)
fun requestRoute(routeInfo: RouteInfo)
fun requestRouteWithWaypoints(request: RouteWithWaypointsRequest)
fun release()

// ai.pleos.playground.navi.data
data class TripRequest(
    val latitude: Double, val longitude: Double,
    val poiId: String, val poiName: String, val poiSubId: String?
)
data class RouteInfo(
    val latitude: Double, val longitude: Double,
    val displayLatitude: Double? = null, val displayLongitude: Double? = null,
    val poiName: String, val poiId: String, val address: String, val poiSubId: String,
    val routeOption: RouteOption = RouteOption.RECOMMENDED,
    val poiProvider: String = "", val isMobileRequest: Boolean = false
)
data class RouteWithWaypointsRequest(
    val destination: RouteInfo, val waypoints: List<RequestWaypointInfo>
)
data class TripInfo(
    val distance: Int, val eta: Int, val estimatedSocAtDest: Int,
    val energyConsumption: Double, val summary: TripSummary
)
```

`SeatArea`는 `ai.pleos.playground.vehicle.api.model.area.SeatArea`, `WindowArea`도 같은 model.area 패키지다. 생성자는 `constructor(vararg values: SeatValue)` / `constructor(vararg values: WindowValue)`다. 좌석 값은 `ROW_1_LEFT`, `ROW_1_RIGHT`, `ROW_2_LEFT`, `ROW_2_RIGHT` 등이다. `TemperatureUnit.CELSIUS`와 `FAHRENHEIT`를 사용한다. `LightSwitch.ON`/`OFF`, `MoveControl.FORWARD`/`BACKWARD`/`STOP`을 사용하며 enum ordinal을 프로토콜 코드로 보내지 않는다.

공조 온도 단계는 `HvacTemperatureConfigArray.incrementCelsius` 등으로 구성한다. 캡처한 구역을 리스너 클로저에 보관한다. 예컨대 `onHvacTemperatureUpdated(temperature: Float?)` 자체에는 area 인자가 없다.

독서등 쓰기 권한은 `CONTROL_CAR_LIGHTS`; 앰비언트 밝기·색상 setter는 실제 선언상 `CAR_LIGHTS`다. 공조 쓰기는 `CONTROL_CAR_CLIMATE`, 창문 쓰기는 `CONTROL_CAR_WINDOWS`다. 이 권한은 구현 manifest의 매핑 기준이다.

## 4. 상태·확인 정책

1. 화면의 원하는 값(desired)과 마지막 관측값(observed)을 분리한다.
2. 명령은 `pending → accepted → observed` 또는 `failed`/`unverified`로 추적한다. getter/리스너가 있는 기능에만 관측 확인을 적용한다.
3. 명령 콜백의 null, 조회 timeout, 연결 끊김을 성공으로 바꾸지 않는다. 고정 SDK 지연을 약속하지 않는다.
4. 여러 구역 장면 적용은 원자 트랜잭션이 아니다. 성공 항목, 실패 항목, 미확인 항목을 표시하고 실패 항목만 재시도한다.
5. 사용자가 수동으로 바꾼 값은 최신 관측값으로 반영한다. 장면을 무한 재적용하지 않는다.
6. 새 명령 뒤 이전 요청의 비동기 응답이 와도 새 desired를 덮어쓰지 않는다. 리스너에 SDK timestamp/sequence가 없는 경우 발생 시각을 확정할 수 없으므로 재조회와 세대 구분으로 앱의 요청 경쟁을 관리한다.
7. 앱 재시작 때 로컬 저장 모드를 실제 차량 상태로 간주하지 않는다. 최신 값 재조회 후 화면을 맞춘다.

## 5. 주행·정차 관련 관측 범위

제공된 Vehicle 선언에는 `getCurrentBrakePedalStatus`/`registerBrakePedalStatus`가 있다. 이는 브레이크 페달 눌림이며 정차/P 기어 증명은 아니다. `getCruiseControlTargetSpeed`도 실제 차량 속도가 아니다. 주행 모드 값은 NORMAL/ECO/SPORT다. 주차 브레이크 telltale enum은 있으나 변속기 P 상태와 같지 않다.

따라서 구현에서 `motionState: parked`를 제공 SDK의 측정값으로 만들면 안 된다. 데모의 주행/정차 전환은 명시적 simulator 입력으로 두거나, 향후 호스트가 제공할 별도 확인 계약으로 문서화한다. 모르는 상태를 정차로 간주하지 않는다. 사용자가 실행한 공간 설정과 차체/좌석 자동 이동은 구분하며 자동 평탄화 기능은 만들지 않는다.

## 6. 내비·에너지 의미

- 좌표 순서: latitude, longitude. 생성 시 named arguments 권장.
- TripInfo.distance는 m, energyConsumption은 Wh, estimatedSocAtDest는 %. eta는 초이나 epoch/소요시간 기준은 manifest가 확정하지 않는다. 제공자 기준 확인 전 임의 `now + eta` 금지.
- 내비 시뮬레이터가 여행 소요시간을 보여줄 때 자체 `durationMinutes`로 이름을 정하고 SDK eta를 변환한 실제 데이터처럼 표현하지 않는다.
- 예상 도착 SOC에는 충전 경유가 포함될 수 있다. 현재 SOC와의 차이만으로 주행 에너지나 캠핑 숙박 가능 시간을 계산하지 않는다.
- 주행 계획 조회는 목적지 설정이나 길안내 시작이 아니다. 내비 이벤트 순서와 요청당 정확히 한 번 응답을 전제하지 않는다.
- 배터리 총 kWh, 실시간 차박 전력, V2L 출력, CO2/습도, 차량 자동 수평 맞춤은 제공된 핵심 기능으로 추정해 만들지 않는다.

## 7. 예약·날씨·상거래 계약

- 디렉터리·예약·날씨·판매처 어댑터를 분리한다. 지역/공급자가 달라도 `provider`, `sourceMode`, `observedAt`, 유효 범위, missing 상태를 유지한다.
- 예약 가능 여부는 날짜 범위 전체와 인원/사이트 조건에 대한 값이다. 앱 계약의 `available`, `limited`, `unavailable`, `unknown`을 구분한다.
- 특정 판매처의 잔여 수로 캠핑장 전체 예약률을 계산하지 않는다. 실제 예약 수/예약률은 공급자가 해당 모집단 수치를 함께 제공할 때만 표시한다.
- 캠핑장 차박 허용, 택배 수령 가능, 캠핑 전기, EV 충전은 각각 별도 근거/조건이다. 주차가 가능하다고 숙박을 허용한다고 추정하지 않는다.
- 날씨는 캠핑장 위치·체류 시간대와 연결한다. 날짜 변경 때 예전 날씨가 남지 않도록 한다. 계절 전망을 확정 예보로 만들지 않는다.
- 상품 가격/재고/배송비는 공급자 값 또는 명시된 fixture다. 장바구니 수량·총액은 계산하며 서버에서도 재검증한다.
- 배송 목적지는 `home` 또는 `campsite`. 캠핑장은 수령 허용, 배송주소, 수령자/예약 정보, 예상 도착일의 체류 일정 적합성을 확인한다. 허용하지 않거나 일정 밖이면 이유와 자택 대안을 보여준다.
- 데모 결제는 실제 카드정보를 수집하지 않으며 ‘데모 주문’으로 생성한다. 주문 중복 방지 키, 장바구니 비우기, 재방문 주문 조회는 작동해야 한다. 실제 승인번호·실제 배송중으로 꾸미지 않는다.

## 8. 최소 인수 테스트

1. 날짜/인원/차박 조건 검색이 목록을 바꾸고 빈 결과와 unknown 예약 상태를 구분한다.
2. 캠핑장 선택·저장 → 내 캠핑에서 같은 날짜·인원·장소가 보이고 새로고침 후 유지된다.
3. 일정 변경이 예약/날씨 데이터 갱신을 유발한다. 오래된 결과가 최신 선택을 덮어쓰지 않는다.
4. 내비 요청 → 요청 상태 → demo event 결과가 순서대로 표시되며 예상 주행 조회와 구분된다.
5. ‘앞 독서/뒤 휴식’ 장면이 구역별 독서등·앰비언트 값을 다르게 적용하고 관측 결과를 보여준다.
6. 한 기능 실패 주입 시 나머지 성공을 유지하고 전체 성공으로 표시하지 않는다. 재시도는 실패 기능만 수행한다.
7. 연결 끊김·null·미지원 기능이 0/false로 바뀌지 않는다. capability 밖 입력을 거부한다.
8. 장면 적용 후 수동 온도 변경이 UI에 반영되고 장면이 덮어쓰지 않는다.
9. 상품 추가·수량 변경·삭제·총액 계산·장바구니 저장이 작동한다.
10. 집 배송 주문이 데모로 생성되고 캠핑장 배송은 수령조건·배송일 검사 후 생성된다. 조건 불충족 경로도 검증한다.
11. 결제 이중 클릭/재전송이 주문 한 개만 만들며 실패 때 장바구니를 잃지 않는다.
12. 화면 녹화는 실제 앱 클릭·상태 전이를 기록한다. 영상의 데이터/차량 동작이 데모임을 짧게 표시한다.

## 9. PRD 권장 범위

세 주 메뉴 찾기/내 캠핑/차박을 유지하고 장바구니·쇼핑은 캠핑 준비 흐름의 보조 진입점으로 둔다. 핵심 데모는 장소 결정 → 일정 저장 → 날씨/주행계획 → 장면 적용 → 필요한 용품 구매와 배송지 선택의 한 연속 여정이다. 초기 계획서의 기능 확장인 상거래는 돌아가는 demo adapter로 포함할 수 있으나 실제 파트너 연동 또는 결제가 이루어졌다고 주장하지 않는다.
