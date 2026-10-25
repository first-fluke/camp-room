# Kotlin 메인 프런트엔드 정정

사용자가 명시적으로 메인 프런트엔드를 Pleos Kotlin 앱으로 정정했다. 기존 브라우저 가정은 잘못이므로 구분된 OMA lineage camp-room-native-v2/session20261025-045159로 실행한다. 이전 web 작업은 partial/superseded로 보존하며 기존 API22tests 결과는 재사용한다.

- mobile/native-platform: 실제 Gradle Android 모듈, demo/Pleos flavor, SDK 명세/수명주기 경계
- frontend/native-ui: Kotlin Compose UI, HTTP 모델/저장소/ViewModel, 전체 캠핑·커머스 여정
- qa/native-qa: 실제 APK 설치·Android emulator 조작·API 결과·MP4
- refactor/native-refine: 최종 native/SDK 경계 검토

인수는 browser screenshot이 아니라 실제 Android app이며 demo APK, 실제 SDK flavor compilation, 실차 검증 여부를 구분한다.
