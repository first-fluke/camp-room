# 앱 진단과 사용 흐름 분석

앱은 로컬 진단 로그를 남기고 설정한 경우 Sentry와 PostHog로 정해진 이벤트를 보낸다. 구현은 [CampTelemetry](../apps/pleos/app/src/main/java/camp/room/app/observability/CampTelemetry.kt)와 [TelemetryPolicy](../apps/pleos/app/src/main/java/camp/room/app/observability/Telemetry.kt)에 있다. 외부 전송은 기본으로 꺼져 있다.

## 설정

| 환경변수 | Gradle 속성 | 기본값 |
| --- | --- | --- |
| `CAMP_TELEMETRY_CLOUD_ENABLED` | `campTelemetryCloudEnabled` | `false` |
| `CAMP_SENTRY_DSN` | `campSentryDsn` | 빈 값 |
| `CAMP_POSTHOG_API_KEY` | `campPosthogApiKey` | 빈 값 |
| `CAMP_POSTHOG_HOST` | `campPosthogHost` | `https://us.i.posthog.com` |

이 값은 빌드할 때 `BuildConfig`에 들어간다. Sentry DSN과 PostHog 프로젝트 수집 토큰처럼 클라이언트에 넣도록 제공된 값만 사용한다. 개인 계정 토큰, 관리 API 토큰과 Turso·데이터 공급자 비밀키를 넣지 않는다. 외부 전송 플래그와 해당 프로젝트 설정이 모두 있어야 그 서비스가 활성화된다.

## 기록하는 정보

화면, 사용자 동작의 종류, API·SDK 작업, 결과는 enum으로 정의한다. 지연 시간은 0~600000ms, HTTP 상태 코드는 100~599 범위만 허용한다. 오류에는 예외의 클래스 이름만 기록한다. 임의 문자열, 음성 인식 문장, 위치, 차량 값, 주소, 주문 내용과 요청·응답 본문은 이벤트 속성으로 받지 않는다.

일반 이벤트에는 빌드 종류를 나타내는 `runtime=demo|pleos`를 기록한다. 주문 동작과 주문 생성 API 이벤트에는 두 빌드 모두 `order_mode=demo`를 붙인다. `pleos`는 빌드 종류이며 실차 검증 여부를 뜻하지 않는다.

크래시 요약은 예외 종류와 최대 32개의 클래스·메서드·행 번호 프레임을 남긴다. 예외 메시지, 원인 객체와 스레드 이름은 요약에 포함하지 않는다. 로컬 기록이 실패해도 기존 치명적 예외 처리기로 제어를 넘긴다.

## 로컬 진단

앱 전용 저장소의 `diagnostics/events.jsonl`과 `events.previous.jsonl`을 사용한다. 각 파일은 최대 128KiB이며 한 기록이 이 크기를 넘으면 저장하지 않는다. 일반 이벤트는 최대 128개 대기열에서 비동기로 처리하고 대기열이 가득 차면 오래된 대기 작업부터 버린다. 크래시 기록은 동기적으로 시도한다.

이 로그는 앱 내부 파일이다. 외부 저장소나 logcat에 원문 예외를 남기는 경로로 사용하지 않는다.

## 외부 도구

Sentry Android Core 8.61.1은 수동으로 초기화한다. 기본 PII 수집, 화면 캡처, 뷰 계층, breadcrumb과 tracing은 끈다. 설정된 경우 ANR 감지를 사용한다. 전송 직전에는 원본 이벤트를 그대로 보내지 않고 예외 종류와 제한된 프레임으로 다시 구성한다.

PostHog Android 3.72.8은 앱에서 정한 화면·동작 이벤트만 보낸다. 자동 화면 수집, replay, push, feature flag 사전 조회와 person profile 생성을 끈다. 프로세스마다 익명 UUID를 쓰고 전송 전 허용 목록 밖의 속성을 제거한다. 위치 추정용 GeoIP도 끈다.

로컬 기록, SDK 초기화와 실제 SaaS 수신은 서로 다른 검증 단계다. 현재 소스에 연결을 구현했으며 외부 프로젝트 생성·수집 키 설정과 실제 대시보드 도착 여부는 별도 실행 결과로 확인한다. SDK가 빌드됐다는 이유만으로 외부 수집이 완료됐다고 표시하지 않는다.
