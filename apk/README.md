# 제출 APK 준비 상태

현재 저장소에는 설치 가능한 signed release APK가 없습니다. 기존 로컬 화면 검수와 시연에 사용한 APK는 `pleosDebug`이며 release로 제출하거나 이름만 바꾸지 않았습니다.

- 공식 제출용 경로: 이 폴더의 `camp-room-pleos-release.apk` 또는 GitHub Releases의 동명 signed release 자산.
- 현재 blocker: release signingConfig가 없고 기존 승인된 release 서명키를 확인하지 못했습니다. 새 키를 임의 생성하지 않았습니다.
- release에는 심사 환경에서 접근 가능한 실제 HTTPS API 주소가 필요합니다. 기본 `api.example.invalid`는 실행 가능한 서비스가 아닙니다.
- 서명 준비 후 `:app:assemblePleosRelease -PcampApiBaseUrl=https://YOUR_BACKEND/api/v1`로 생성하고, `apksigner verify --verbose --print-certs`, applicationId/minSdk/debuggable 검사, 실제 설치·실행 검수를 수행해야 합니다.
- 최종 APK가 생기면 바이트 수·SHA-256·서명 인증서 지문·소스 커밋·검증 범위를 이 문서에 기록합니다. 아직 없는 APK의 해시나 설치 성공을 주장하지 않습니다.

서명키·비밀번호·SDK 배포물은 이 폴더나 GitHub에 넣지 않습니다.
