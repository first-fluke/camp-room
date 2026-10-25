# Camp Room native Android 앱

메인 프런트엔드는 Kotlin·Jetpack Compose Material3 Android 앱이다. main sourceSet에는 화면·HTTP 저장소·ViewModel을, demo에는 emulator 시뮬레이터 연결을, pleos에는 실제 SDK 직접 연결을 둔다.

## 빌드

```bash
bash apps/pleos/gradlew -p apps/pleos :app:assembleDemoDebug
bash apps/pleos/gradlew -p apps/pleos :app:assemblePleosDebug -PcampApiBaseUrl=https://YOUR_BACKEND/api/v1
```

공식 Maven SDK 좌표는 Vehicle2.3.2와 NaviHelper3.0.5다. 사용자 제공 `docs/product/pleos-sdk.spec.jsonl`이 SDK 계약 기준이다. 실제 Gradle 빌드/앱 실행 결과는 별도 검증 기록을 따른다. 실제 차량에서 검증했다고 주장하지 않는다.

## 실제 차량 연결

Pleos PlatformBridge는 CapabilityConfig의 READ/WRITE와 실제 지원 area/bounds/temperature increment를 조회한다. getter capability를 setter와 별도로 확인한다. SDK가 제공하는 실제 SeatArea/WindowArea를 제어별로 선택하며 지원되지 않는 전체 구역을 임의로 만들어내지 않는다. 조명·차광 UI percent는 실제 지원 범위로 명시적으로 환산한다.

MotionProvider는 신뢰 가능한 host 신호가 필요하다. 기본은 UNKNOWN이며 brake pedal/cruise target을 정차 또는 P기어로 바꾸지 않는다. 이 신호를 연결하기 전에는 실제 차량 설정을 차단한다. 제공 SDK에 없는 current speed/gear API를 만들어내지 않는다.

SOC listener를 어댑터가 소유하고 수명주기에 맞춰 해제한다. listener의 새 sample 뒤에 늦은 초기 getter가 도착해 값을 덮어쓰지 않는다. 세션 generation과 control/zone 요청 token으로 중단·새 명령 이후의 늦은 callback과 getter를 차단한다. close는 실패해도 release를 시도한다. Navigation 연결 callback과 listener도 generation으로 관리한다.

- OBSERVED: 별도 getter 관측값이 요청 목표와 허용 오차 내에서 일치한다.
- OBSERVED_MISMATCH: 관측값이 목표와 다르므로 적용 완료로 표시하지 않는다.
- ACKNOWLEDGED: setter callback을 받았으나 별도 관측은 확인하지 못했다.
- SENT_UNVERIFIED: 좌석 메모리처럼 성공 callback이 없는 명령을 보냈다.
- FAILED/BLOCKED: 오류·권한·미지원·정차 미확인 상태를 숨기지 않는다.

충전 목표 잔량 getter를 만들어내지 않는다. 실제 내비는 onTripInfo/onRouteStarted 이벤트 뒤에만 결과를 확인한다. TripInfo 거리 m→km, eta s→min, energyConsumption Wh→kWh를 변환하고 실제 EV_CHARGING waypoint로 충전 경유를 표시한다.

`python apps/pleos/verify_contracts.py`는 저장된 exact binding과 Kotlin SDK 메서드를 사용자 JSONL에 대조한다. 이 검사 자체는 컴파일·APK·emulator·실차 검증의 대체물이 아니다.
