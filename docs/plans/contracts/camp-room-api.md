# Camp Room API 계약 v1

Base `/api/v1`. JSON camelCase. 날짜 YYYY-MM-DD, 금액 KRW 정수. Errors `{detail: string}`; validation 422, missing 404, conflict409. Android demo app -> emulator host10.0.2.2:8000; real Pleos uses configured backend base URL. Kotlin frontend와 backend 담당자는 이 파일의 endpoint/field 형식을 공유한다. 추가 호환 필드는 허용하고 변경은 동료에게 알린다.

## GET /bootstrap

Response `{mode, campsites: Campsite[], trip: Trip|null, weather: Weather, vehicle: Vehicle, products: Product[], cart: Cart, orders: Order[], providers: ProviderStatus[]}`. mode=demo|live|mixed. 데모 응답은 source/provider 이름에 데모를 포함한다. campsites 목록은 기본 검색 결과다.

## 장소

`GET /campsites?region=&checkIn=&checkOut=&guests=2&carCamping=true&facilities=shower,electricity` -> `{items:Campsite[],total:number,source,mode}`.

`GET /campsites/{id}` -> Campsite.

Campsite `{id,name,region,address,latitude,longitude,description,imageUrl,nightlyPrice:number|null,facilities:string[],carCampingAllowed:boolean|null,evChargingAllowed:boolean|null,rules:string[],checkInTime,checkOutTime,delivery:{acceptsParcels:boolean,location,instructions},availability:{status:'available'|'limited'|'unavailable'|'unknown',remaining:number|null,coverage,checkedAt,provider,demo:boolean},source:{name,updatedAt,mode}}`.

시설 code: shower,electricity,pets,toilet,water. 실제 GoCamping은 장소/시설 출처이며 예약 재고 공급자가 아니다. 없는 요금은 null, 차량 숙박 허용 정보 미제공은 null, 가용성은 unknown으로 유지한다.

`POST /availability/query` body `{campsiteId,checkIn,checkOut,guests}` -> Availability.

## 여행·날씨·내비

`GET /trip` -> Trip|null. `PUT /trip` body `{campsiteId,checkIn,checkOut,guests}` -> Trip.

Trip `{campsiteId,checkIn,checkOut,guests,navigationStarted:boolean,updatedAt}`.

`GET /weather?campsiteId=&checkIn=&checkOut=` -> Weather `{source,updatedAt,summary,overnightLow:number|null,rainProbability:number|null,forecast:[{time,temperature:number|null,condition,rainProbability:number|null}],demo:boolean,status:'available'|'unavailable',message?}`. APIhub key 또는 serviceKey 방식 모두 지원 가능한 어댑터로 분리. 예보 제공 기간 밖은 unavailable; 장기 예보 생성 금지.

`POST /navigation/preview` body `{campsiteId}` -> `{distanceKm,durationMinutes,consumptionKwh,arrivalSoc,chargingStop:boolean,mode:'demo',message}`. `POST /navigation/start` same body -> `{started:true,mode:'demo',message,trip}`. SDK 네이티브 연결은 별도 host port.

## 차량

`GET /vehicle` -> Vehicle `{mode:'demo',motionState:'parked'|'moving'|'unknown',soc,cabinTemperature,targetTemperature,front:{readingLight:boolean,ambientBrightness:number,blindLevel:number},rear:{readingLight:boolean,ambientBrightness:number,blindLevel:number},doorsClosed:boolean|null,windowsClosed:boolean|null,lastSceneId:string|null,updatedAt}`.

`POST /vehicle/scenes` body `{sceneId:'load'|'together'|'split'|'sleep'|'departure',failControl?:string}` -> `{vehicle:Vehicle,results:CommandResult[],status:'completed'|'partial'|'blocked'}`.

`POST /vehicle/controls` body `{control:string,value:number|boolean,zone?:'front'|'rear'}` -> same result. Required controls targetTemperature,readingLight,ambientBrightness,blindLevel. CommandResult `{control,target,status:'applied'|'failed'|'unsupported'|'blocked',observed:number|boolean|null,confirmation:'observed'|'acknowledged'|'unverified',message}`. Demo observed는 simulator 상태이며 실제 Vehicle 측정으로 주장하지 않는다.

## 준비물·장바구니·주문

`GET /products?campsiteId=` -> `{items:Product[]}`. Product `{id,name,price,stock,category,recommendationReason,imageUrl}`.

`GET /cart` -> Cart. `POST /cart/items` body `{productId,quantity}`는 기존 수량에 추가 -> Cart. `PATCH /cart/items/{productId}` body `{quantity}`는 수량 교체 -> Cart. `DELETE /cart/items/{productId}` -> Cart.

Cart `{items:[{productId,name,quantity,unitPrice,lineTotal,imageUrl}],subtotal,shipping,total}`.

`POST /checkout/quote` body `{destination:'home'|'campsite',campsiteId?:string,checkIn?:string,checkOut?:string}` -> Quote `{eligible:boolean,reason:string|null,destination,eta:string,receivingConditions:string[],subtotal,shipping,total}`. 숙박은 checkIn <= eta < checkOut 조건. 거절 이유는 UI에서 바로 표시 가능한 한국어.

`POST /orders` body `{idempotencyKey,destination:'home'|'campsite',campsiteId?,checkIn?,checkOut?}` -> Order `{id:'DEMO-...',status:'demo_confirmed',mode:'demo',createdAt,items,total,destination,eta,receivingConditions}`. 서버 견적 재검증; 빈 장바구니/재고 초과/수령 불가/기간 불일치 차단; idempotency 재시도는 장바구니 비워진 이후에도 기존 주문 반환. 동일key 다른 payload409. `GET /orders` -> `{items:Order[]}`.

## 외부 데이터 설정

루트 `.env`는 서버에서만 로드한다. `.env.example` 공개, 실제 `.env` gitignore. `CAMP_DATA_MODE=demo` 기본; auto는 credential 존재 시 live provider를 요청하고 실패 시 source와 error를 명시한 fallback; live는 가짜 성공 없이 unavailable/error. `GOCAMPING_SERVICE_KEY`, `KMA_API_HUB_KEY`, optional `KMA_SERVICE_KEY`, `CAMP_DB_PATH`, `CAMP_API_BASE_URL`(웹 서버 전용 프록시 origin). 각 ProviderStatus `{name,configured:boolean,status,mode,message}`에는 비밀값을 포함하지 않는다. 토큰을 로그, 클라이언트 bundle, 문서, receipt에 출력하지 않는다.

## 수령인 상세 추가 계약

견적/주문 body는 recipientName, address, siteReference를 추가로 받는다. Home 주소는 필수 비어 있지 않은 문자열이며 수령인 이름도 서버가 검증한다. Campsite 배송은 서버의 실제 receiving location을 사용하고 사용자 siteReference를 함께 저장한다. Order는 recipientName, address, siteReference를 그대로 반환한다. 예시는 가상 시연 정보다.

## Android native PlatformBridge

HTTP 차량/내비 endpoint는 demo flavor에서만 사용한다. pleos flavor는 common ViewModel의 같은 PlatformBridge 계약을 로컬 Vehicle/NaviHelper SDK로 구현한다. 서버 bootstrap의 vehicle은 native snapshot으로 덮어쓴다. 공급자·캠핑장·날씨·커머스는 동일한 HTTP 계약을 유지한다. native RoutePreview는 onTripInfo에서 distance m→km, eta s→min, energyConsumption Wh→kWh를 변환하며 chargingStop은 실제 routePoints EV_CHARGING waypoint를 기반으로 한다.
