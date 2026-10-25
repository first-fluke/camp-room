# Camp Room native Android 앱

메인 프런트엔드는 Kotlin·Jetpack Compose Material3 Android 앱이다. main sourceSet에는 화면·타입이 있는 API 저장소·ViewModel을, demo에는 emulator 시뮬레이터 연결을, pleos에는 실제 SDK 직접 연결을 둔다. 운영자 웹은 별도 `apps/admin`이며 이 앱의 프런트엔드를 대체하지 않는다.

## 빌드

```bash
bash apps/pleos/gradlew -p apps/pleos :api-client:test :app:assembleDemoDebug
bash apps/pleos/gradlew -p apps/pleos :app:assemblePleosDebug -PcampApiBaseUrl=https://YOUR_BACKEND/api/v1
```

JDK17, Gradle8.11.1, AGP8.9.2, Kotlin2.1.20, compileSdk/targetSdk35를 사용한다. `demo`는 `http://10.0.2.2:8000/api/v1`에 연결하며 데모 sourceSet에서만 cleartext를 허용한다. `pleos`에는 실제 배포한 HTTPS API 주소를 전달해야 한다.

공식 Maven 의존성은 Vehicle2.3.2, NaviHelper3.0.5, TextToSpeech3.3.1, SpeechToText3.3.1, FusedLocation1.1.3의 다섯 모듈이다. 사용자 제공 `docs/product/pleos-sdk.spec.jsonl`이 SDK 계약 기준이며 [전체 모듈 사용표](../../docs/product/SDK_CAPABILITY_MATRIX.md)에 각 사용 범위를 기록했다. 실제 Gradle 빌드/앱 실행 결과는 별도 검증 기록을 따른다. 실제 차량에서 검증했다고 주장하지 않는다.

## Hilt와 생성 API 클라이언트

Hilt2.56.2가 Application→Activity→ViewModel의 생성과 저장소·flavor별 PlatformBridge 주입을 담당한다. 실제 SDK 객체와 재연결용 SDK 팩터리는 pleos 전용 Hilt 모듈에서 제공한다. 어댑터가 initialize/start/stop/release 수명주기를 관리하며, main 코드가 서비스 객체를 직접 생성하지 않는다.

서버의 명시적 Pydantic 응답 모델을 `apps/api/openapi.json`으로 내보낸다. OpenAPI Generator7.26.0의 Kotlin `jvm-ktor`와 `kotlinx_serialization` 옵션으로 DTO·서비스 인터페이스를 생성한다. `:app:openApiGenerate`는 앱·클라이언트 컴파일의 선행 작업이며 생성물은 `app/build/generated/openapi` 아래에 둔다. `:api-client` JVM 모듈은 이 생성물을 사용한다. 앱 저장소는 생성된 서비스의 타입이 있는 메서드를 호출하며 HTTP 경로·JSON 요청을 수동 조립하지 않는다.

런타임은 Kotlin2.1.20과 맞는 Ktor3.1.3·kotlinx.serialization1.8.1을 사용한다. 공식 jvm-ktor 템플릿의 `httpClientConfig` 훅으로 Kotlinx ContentNegotiation을 주입하며 템플릿을 복제하지 않았다. JSON scalar union은 `JsonPrimitive`로 매핑해 `true` 같은 값이 빈 객체로 변하지 않도록 한다. `:api-client:test`는 실제 생성 클라이언트와 MockEngine으로 요청·응답 및 scalar 직렬화를 확인한다.

```bash
.venv/bin/python apps/api/scripts/export_openapi.py --check
bash apps/pleos/gradlew -p apps/pleos :app:openApiGenerate :api-client:test
```

## 언어와 보조 기능

기본 영어·한국어 리소스를 제공하고, 앱에서 시스템 언어/한국어/영어를 선택해 저장한다. 날짜와 KRW 금액은 선택 언어로 표시하며 TTS·STT에 동일한 locale을 전달한다. 공급자가 제공한 캠핑장 이름·주소·규칙 같은 원문 데이터는 임의로 번역한 사실로 바꾸지 않는다.

FusedLocation은 최신으로 알려진 좌표와 수신 시각을 구분한다. 500m 도착 알림의 등록과 실제 진입·이탈 이벤트를 구분하며, 권한을 실행 시 요청한다. 음성 브리핑은 SDK/Android 재생 시작 이벤트를 확인하며, 음성 인식은 최종 결과만 사용해 장면·길 안내를 미리보기로 제안한다. 인식만으로 차량 설정이나 결제를 실행하지 않는다.

## 실제 차량 연결

Pleos PlatformBridge는 CapabilityConfig의 READ/WRITE와 실제 지원 area/bounds/temperature increment를 조회한다. getter capability를 setter와 별도로 확인한다. SDK가 제공하는 실제 SeatArea/WindowArea를 제어별로 선택하며 지원되지 않는 전체 구역을 임의로 만들어내지 않는다. 조명·차광 UI percent는 실제 지원 범위로 명시적으로 환산한다.

MotionProvider는 신뢰 가능한 host 신호가 필요하다. 기본은 UNKNOWN이며 brake pedal/cruise target을 정차 또는 P기어로 바꾸지 않는다. 이 신호를 연결하기 전에는 실제 차량 설정을 차단한다. 제공 SDK에 없는 current speed/gear API를 만들어내지 않는다.

SOC listener를 어댑터가 소유하고 수명주기에 맞춰 해제한다. listener의 새 sample 뒤에 늦은 초기 getter가 도착해 값을 덮어쓰지 않는다. 세션 generation과 control/zone 요청 token으로 중단·새 명령 이후의 늦은 callback과 getter를 차단한다. close는 실패해도 release를 시도한다. Navigation 연결 callback과 listener도 generation으로 관리한다.

- OBSERVED: 별도 getter 관측값이 요청 목표와 허용 오차 내에서 일치한다.
- OBSERVED_MISMATCH: 관측값이 목표와 다르므로 적용 완료로 표시하지 않는다.
- ACKNOWLEDGED: setter callback을 받았으나 별도 관측은 확인하지 못했다.
- SENT_UNVERIFIED: 좌석 메모리처럼 성공 callback이 없는 명령을 보냈다.
- FAILED/BLOCKED: 오류·권한·미지원·정차 미확인 상태를 숨기지 않는다.

충전 목표 잔량 getter를 만들어내지 않는다. 실제 내비는 onTripInfo/onNavigationStatus.ROUTE_DRIVE 이벤트 뒤에만 결과를 확인한다. TripInfo 거리 m→km, eta는 원본 seconds/basis 미확인으로 보존, energyConsumption Wh→kWh를 변환하고 실제 EV_CHARGING waypoint로 충전 경유를 표시한다.

`python apps/pleos/verify_contracts.py`는 저장된 exact binding과 Kotlin SDK 메서드를 사용자 JSONL에 대조한다. 이 검사 자체는 컴파일·APK·emulator·실차 검증의 대체물이 아니다.
