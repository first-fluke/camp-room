# Camp Room 전체 SDK 검토·적용 매트릭스

기준은 사용자가 제공한 `pleos-sdk.spec.jsonl` 전체 1,652개 declaration record다. 샘플 코드의 오래된 버전이나 메서드를 현재 API로 확대하지 않는다. 전체 모듈은 5개다. `sdk-module-coverage.json`은 버전·원문 record 수·제품 책임을 기계 판독 가능하게 보존한다.

| SDK | 버전 | 원문 record 수 | Camp Room 실제 사용 |
|---|---|---:|---|
| Vehicle | 2.3.2 | 1,067 | 현재 SOC·실내 측정/목표온도·지원구역·조명·차광, capability/권한·정차 호스트 신호를 확인한 장면/제어 |
| NaviHelper | 3.0.5 | 454 | 목적지 이동 예상, 실제 주행 안내 상태 확인, 경유지/충전 경유 해석 |
| TextToSpeech | 3.3.1 | 44 | 캠핑장 일정·날씨·규칙·차량 정보를 선택한 한국어·영어로 안내 |
| SpeechToText | 3.3.1 | 50 | 차량 마이크의 최종 인식 텍스트를 내비/차박 명령 미리보기로 변환 |
| FusedLocation | 1.1.3 | 37 | 차량 최근 위치를 기준으로 주변 장소 정렬, 캠핑장 500m 진입·이탈 알림 |

모든 SDK를 앱에 무조건 같은 기능으로 노출하지 않는다. 각 모듈은 이 제품에 맞는 역할로 사용하고 불필요한 모든 API를 호출했다고 주장하지 않는다. 실제 Pleos flavor는 공식 Maven 의존성으로 5개를 모두 컴파일한다. demo flavor는 네이티브 Android에서 명시적인 차량/내비 시뮬레이터와 기기 위치/음성 서비스를 사용한다.

## Vehicle

실제 SDK의 check*Capabilities, AccessType, supported SeatArea/WindowArea, 범위와 공조 increment를 읽는다. getter의 지원 여부와 setter 지원을 구분한다. SDK에 current road speed/P gear가 제공되지 않으므로 기본 MotionProvider는 UNKNOWN이다. 검증된 호스트 정차 신호가 없으면 제어를 차단한다.

setter callback, 별도 getter observation, 목표와 다른 관측값, 성공 callback 없는 좌석 메모리, 충전 목표 getter 부재를 구분한다. SOC listener는 VehicleListener.onFailed를 포함해 구현하며 등록 세대와 명령별 token으로 늦은 응답을 차단한다.

## NaviHelper

requestTrip 결과는 onTripInfo로 받는다. distance는 m→km, energyConsumption은 Wh→kWh로 변환한다. eta는 seconds라는 점만 명세로 확인되며 duration/epoch 기준은 정해지지 않았으므로 실제 SDK 응답의 durationMinutes를 임의 계산하지 않는다. 원본 etaSeconds와 basis 미확인을 보존한다.

onRouteStarted는 경로 탐색 시작이며 실제 주행 안내 시작으로 단정하지 않는다. 앱은 onNavigationStatus의 ROUTE_DRIVE를 관측한 뒤에만 started=true로 표시한다. 충전 경유는 summary.routePoints의 EV_CHARGING+WAYPOINT에 근거한다. timeout/단절을 성공으로 바꾸지 않는다.

## FusedLocation

isSupported로 지원 여부를 확인한 뒤 initialize한다. registerFusedLocationCallback과 getLastKnownLocation을 사용하며 최근 위치는 현재 위치로 단정하지 않는다. timestampMillis의 원점/스케일을 앱이 추정하지 않고 sdkTimestampMillis 원본과 앱 receivedAt을 분리한다. null·NaN·미관측 confidence는 유효값으로 바꾸지 않는다.

FUSED_LOCATION은 manifest에 선언하고 실제 기능 사용 시 runtime 권한을 요청한다. addProximityAlert의 반환 Unit은 등록 성공이 아니다. onRegistered(alertId)를 받아 ID를 보관하고 onTransition의 ENTER/EXIT를 별도 감지 상태로 표시한다. 등록 알림으로 차량을 자동 제어하지 않는다. 앱 비활성화 시 callback과 알려진 alertId를 해제하고 대기 중 등록의 ID가 늦게 오면 해제한 뒤 SDK release를 진행한다. 등록/해제 실패는 cleanup_pending 상태로 남기며 해제 완료로 꾸미지 않는다.

## TextToSpeech

ON_DEVICE 모드를 명시하고 initialize listener 후 앱에서 선택한 한국어·영어 Locale을 설정한다. speak의 직접 반환 Unit을 재생 성공으로 표시하지 않는다. EventListener.onStart를 확인한 뒤 재생 시작으로 표시하고 done/stop/error를 구분한다. ON_DEVICE 선택으로 서버 clientSecret을 Android 앱에 넣지 않는다. 서버 모드 registerApp은 별도 제품 요구와 서버 인증 설계 없이 호출하지 않는다.

## SpeechToText

ON_DEVICE initialize와 ResultListener를 준비한 후 request로 차량 마이크 인식을 시작한다. sendAudio/completeAudioSend는 별도의 외부PCM 경로이며 현재 마이크 request 흐름과 섞지 않는다. onEndedRecognition이 최종 텍스트보다 나중이라는 보장이 없으므로 종료 이벤트만으로 final 결과를 없애지 않는다. completed=true인 onUpdated, onError 또는 앱 timeout으로 결과를 결정한다.

인식된 명령은 먼저 미리보기로 보여준다. 음성으로 장면을 골라도 차량 적용 버튼을 별도로 누르며 길안내도 해당 화면에서 확인 후 실행한다. 주문·결제는 음성으로 자동 실행하지 않는다. 직접 입력한 텍스트를 음성 인식 결과로 표현하지 않는다.

## 검증 범위

전체 5개 SDK를 포함한 demo/Pleos APK 모두 실제 Gradle 빌드에 성공한 상태에서 추가 검증을 이어간다. 정확한 최신 실행 결과는 검증 보고서와 OMA 고유 run 기록을 따른다. Android emulator 실행, 기기 위치/음성 서비스의 실제 가용성, Pleos 차량 서비스/권한·실차 동작은 별도 관측 범위다. source checker 자체는 컴파일이나 실차 검증을 대체하지 않는다.
