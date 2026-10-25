# Pleos 디자인·앱 실행 검증

C1 소스로 빌드한 Android 데모 앱에서 **25개 검사가 모두 통과**했다. 영어·한국어·야간 화면, 캠핑장 검색부터 데모 주문까지의 흐름, 앱 프로세스 재생성 후 상태 복구와 로컬 관측성을 확인했다. 실제 녹화에서 만든 전달 영상은 1920×1080, 173.666667초다. 이 결과는 데모 환경에서 확인한 범위이며 실제 차량 동작이나 Pleos 디자인 가이드 전체 준수를 인증하지 않는다.

기준 소스는 `fded228938a8c4a171aad7b179329e08be87bc8d`, OMA 검사의 트리 해시는 `513bde28da83b11c04c1fdd3d240302d2fd24169e880257433fcc13164ada834`다. C1의 [디자인 감사](pleos-design-audit.md)와 기존 OMA 보고서는 변경하지 않았다. 수정 전 25개 요구사항 판정과 이번 데모 실행 검사 25개는 서로 다른 목록이다.

실행 증거 파일은 [검증 묶음](https://github.com/first-fluke/camp-room/releases/download/v1.0.0-demo.2/Camp_Room_Verification.zip)에 포함된다. 묶음을 풀면 이 문서에 표시된 경로에서 각 파일을 확인할 수 있다.

## 빌드·테스트 결과

| 검사 | 확인한 결과 | 범위와 근거 |
| --- | ---: | --- |
| Android 앱 단위 테스트 | 14개 통과 | 영역 보존 1개, 인터랙션 상태 4개, UX 콜백 세대 2개, UX 상태 3개, 관측성 개인정보 보호 4개 |
| 생성 Ktor 클라이언트 | 3개 통과 | `GeneratedKtorContractTest`; 앱·클라이언트 모두 실패·오류 0개 |
| Python API | 33개 통과 | C1의 `apps/api/tests`. 비치명적 Starlette 테스트 클라이언트 사용 중단 예고 1건 기록 |
| 실제 Turso | 20개 검사 통과 | 격리된 데이터의 트랜잭션·동시성·재시도·외래키·지속성. 저장 주문은 미결제 데모로 유지 |
| 관리자 웹 | 5개 테스트·타입 검사 통과 | 인증·동일 출처 요청·경로 보호 등 |
| 관리자 통합 흐름 | 27개 검사 통과 | 인증된 BFF와 실제 Turso의 수령 정책·배송 처리 저장, 복원·로그아웃 |
| Android 데모 실행 | 25개 검사 통과 | 실제 앱 설치·입력·서버 저장·녹화·프로세스 재생성·로컬 이벤트·제어된 크래시 |

앱·클라이언트와 APK 결과는 [native-build-evidence.json](../../artifacts/qa/verification-c1/native-build-evidence.json), API·관리자 결과는 같은 디렉터리의 `c1-oma-api-local.log`·`c1-oma-admin.log`에서 확인했다. 원격 저장소·관리자 통합·네이티브 실행의 전체 목록은 각각 [turso-results.json](../../artifacts/qa/turso-results.json), [admin-integration.json](../../artifacts/qa/admin-integration.json), [native-results.json](../../artifacts/qa/native-results.json)에 있다. 문서 작성 과정에서 검사를 다시 실행하지 않았다.

## 전달 APK

두 파일 모두 **debug 빌드**다.

| 파일 | 크기 | 용도 |
| --- | ---: | --- |
| `app-demo-debug.apk` | 24,568,796바이트 | 차량 상태를 모의하는 데모. API 주소 `http://127.0.0.1:8000/api/v1` |
| `app-pleos-debug.apk` | 41,699,861바이트 | 실제 SDK에 연결하는 Pleos 빌드. API 주소 `https://api.example.invalid/api/v1`은 설정용 자리표시자 |

데모는 호스트 API를 실행하고 `adb reverse tcp:8000 tcp:8000`으로 연결한다. Pleos 빌드는 배포 대상 API 주소와 환경을 설정해야 한다. SDK가 포함된 APK의 빌드 성공과 실제 차량 명령 실행은 별도 증거다.

```text
apps/pleos/app/build/outputs/apk/demo/debug/app-demo-debug.apk
SHA-256 d22706f8007b0ee34b4b8bf9e8fea75885fb290c4c4898eec2ac66ebb3abf480

apps/pleos/app/build/outputs/apk/pleos/debug/app-pleos-debug.apk
SHA-256 41107800628567eac641ea7aa9102b6c0526d9aadf4cd39d437d3417ad87c4e5
```

## 실제 데모에서 확인한 동작

필터가 조건에 맞는 캠핑장을 반환하고, 선택한 장소와 일정이 저장됐다. 내비 동작은 백엔드의 데모 이동 요청에 도달했다. 차박 장면은 모의 앞·뒤 영역에 서로 다른 값을 적용했다. 명령 예시를 선택해 미리보기만 열었을 때는 차량 상태가 바뀌지 않았다. 이 흐름으로 실제 마이크 음성 인식이나 차량 내비게이션 동작을 검증했다고 표현하지 않는다.

장바구니는 서버 가격으로 저장됐고 결제 시도는 **정확히 1개의 미결제 데모 주문**을 만들었다. 수령 캠핑장은 저장한 여행과 일치했고 성공 후 장바구니가 비워졌다. 실제 결제·판매·배송은 수행하지 않았다. 앱 프로세스를 다시 만든 뒤에도 저장 상태가 유지됐다.

[로컬 관측성 결과](../../artifacts/qa/native-telemetry.json)에서는 화면·동작·API·SDK 이벤트와 여행 저장·내비·차박·주문의 기록을 확인했다. 이벤트 속성의 허용 목록, `runtime`·`order_mode` 출처 표기도 통과했다. 제어된 실제 Android fatal exception은 로컬에 저장됐고 메시지·cause·thread 정보는 제외됐다. Sentry·PostHog의 클라우드 수신은 이 기록으로 입증하지 않는다.

## 화면 검토

### 크기·글자 배율별 화면

시연 담당자와 네이티브 구현 담당자가 다음 6개 PNG를 독립적으로 확인했다. 보이는 범위에서 조작을 막는 잘림·겹침·접근 불가 또는 육안상 대비 결함은 발견하지 않았다. 화면 밖 콘텐츠가 스크롤에 따라 잘리는 현상은 정상 viewport 경계로 구분했다.

| 조건 | 채택한 캡처 | 관찰 범위 |
| --- | --- | --- |
| 세로 800×1280 | `native-cabin-portrait-confirmed.png` | 모의 영역 2개, 장면 선택 줄바꿈, 하단 탐색 3개. 적용 버튼은 화면 아래에 있음 |
| 세로 적용 버튼 접근 | `native-cabin-portrait-action.png` | 전체 적용 버튼. 계층정보의 경계 `[463,749][759,829]`, `enabled=true` |
| 작은 창 640×800·글자 배율 1.3 | `native-cabin-narrow-font130.png` | 위·아래 패널 배치와 툴바 줄바꿈 |
| 작은 창 적용 버튼 접근 | `native-cabin-narrow-font130-action.png` | 전체 적용 버튼. 경계 `[41,302][599,366]`, `enabled=true` |
| 가로 1280×800·글자 배율 1.0 | `native-cabin-landscape-confirmed.png` | 해당 스크롤 위치의 수동 온도·독서등·블라인드·적용 버튼 |
| 가로 탐색 화면 | `native-landscape-after-wait.png` | 정상 Discover 화면 |

경로는 `artifacts/qa/design-c1/`다. 게스트 800×1280 또는 640×800 화면을 호스트 1280×800 PNG에 담으면서 생긴 검은 외곽 여백은 앱 레이아웃으로 평가하지 않았다. 이 검사는 에뮬레이터의 디스플레이·창 크기 변경에 해당하며 실제 OEM 분할 화면 인증이 아니다. 버튼 표시·활성화 관찰과 명령 적용 결과도 구분한다.

### 새 녹화에서 확인한 화면

네이티브 구현 담당자는 이번 녹화의 `native-english.png`, `native-dark.png`, `native-discover.png`, `native-campsite-detail.png`, `native-cabin.png`, `native-command-preview.png`, `native-home-delivery.png`, `native-checkout.png`, `native-order.png`를 확인했다. 캠핑장 상세와 실내 화면은 시연 담당자도 확인했다. 실내 화면에는 모의 영역 2개, 앞·뒤 앰비언트 65/15와 블라인드 25/85, 적용 알림이 표시됐다. 이 목록은 앞의 크기별 검토 6개와 구분한다.

내 캠핑 화면은 종료된 원본 WebM의 **130.000초**에서 추출한 [native-trip-from-video-130s.png](../../artifacts/qa/design-c1/native-trip-from-video-130s.png)로 확인했다. 두 담당자가 저장한 여행·데모 날씨·이동 예상·내비 시작 UI를 확인했고, 추출 시점과 원본·프레임 해시는 [추출 기록](../../artifacts/qa/design-c1/native-trip-from-video-130s.json)에 남겼다.

프레임 SHA-256은 `00dcaa035a9448c0457a3ff33261715c025aa56671e34795d5c861e02cccadf0`다. `native-trip.png`는 이전 Discover·처리 중 화면이 찍힌 조기 캡처이므로 내 캠핑 화면의 통과 근거에서 제외했다. 이전 APK를 가리키는 `native-preflight-evidence.json`도 이번 증거로 사용하지 않았다. 영어·한국어·야간 모드 결과는 C1 APK로 다시 실행한 `native-results.json`의 항목을 사용했다.

초기 `native-landscape-initial.png`·`native-landscape-loaded.png`·`native-landscape-ready.png`의 Quickstep/SystemUI ANR 관련 기록과 오래된 `native-cabin-landscape.png`도 디자인 판정에서 제외했다. 원본은 삭제하지 않았다.

## 시연 영상

| 파일 | 실제 확인한 메타데이터 | SHA-256 |
| --- | --- | --- |
| `artifacts/demo/Camp_Room_Native_Raw.webm` | 원본 1280×800, 12fps, 511.999초 | `8395d5749134b91939b66d4690d3a2497f7be0d0a18a827501fd1e290050e29d` |
| `artifacts/demo/Camp_Room_Demo.mp4` | H.264, 1920×1080, 30fps, 173.666667초 | `b65b6ee22d163a099ae6fbeb96489ea9c800105c3c877a470f77e92518e05eb5` |

전달 영상은 실제 원본을 3배속으로 재생하고 끝에 3초 정지 화면을 붙였다. 영상의 한국어 자막과 상단 3× 표시는 편집 요소다. 길이는 예상 계산값 대신 [실제 메타데이터](../../artifacts/demo/demo-metadata.json)를 기록했다.

두 담당자는 전달 영상의 43·73·156·172초 프레임을 독립적으로 확인했다. 실제 내 캠핑, 차박 위 명령 대화상자, 캠핑장 수령 데모 주문, 내 캠핑 복귀·종료 자막이 담겼다. 이 4개 프레임에서 한국어 자막과 상단 3× 표시는 읽을 수 있었고 글자 깨짐·ANR 화면·조작을 가리는 겹침은 관찰하지 않았다. 마지막 ADB 작업 이후 소유한 에뮬레이터를 정상 종료했으며 프로세스 종료를 확인했고 파일·AVD는 삭제하지 않았다.

## 공식 Compose 예제와 배포 범위

Pleos는 공식 디자인 가이드·Figma 자산·컴포넌트 명세와 함께 GitHub에 실제 Compose 예제도 제공한다. 공식 Vehicle 샘플의 [ApiControls.kt](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/blob/959981d960ec71144accb27af5ae5d341e72a644/app/src/main/java/ai/pleos/playground/sample/vehicle/ui/screen/ApiControls.kt)는 Material3의 `ExposedDropdownMenuBox`·`DropdownMenuItem`을 사용하며 [Theme.kt](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/blob/959981d960ec71144accb27af5ae5d341e72a644/app/src/main/java/ai/pleos/playground/sample/vehicle/ui/theme/Theme.kt)도 공개되어 있다. 시연 담당자가 해당 파일과 `libs.versions.toml`을 GitHub API로 직접 확인했다.

별도 설치형 Pleos 디자인 시스템 UI 라이브러리의 공개 배포는 이번에 확인한 공식 조직의 5개 저장소와 Nexus의 14개 좌표·36개 버전에서 찾지 못했다. 이 결과는 공개 조사 범위에 한정한다. 공식 샘플은 구현 참고용이며, 튜토리얼이 UI 전반을 다루지 않는 만큼 디자인 가이드 전체 준수 보증으로 해석하지 않는다.

## OMA 실행 상태

| 기존 세션 | 작업 | 실제 run ID | 상태 |
| --- | --- | --- | --- |
| `20261025-060338` | native-observability | `038f7478-d281-4ab7-9309-104a1c1944d6` | completed, exit 0 |
| `20261025-060338` | pleos-design-alignment | `bdb4832d-bd75-4008-9f09-0aef9f9a78ce` | completed, exit 0 |
| `20261025-060338` | documentation-humanize | `000e197d-bf54-487e-854c-3ef0eaa65709` | completed, exit 0 |
| `20261025-052351` | turso-admin-api | `3cde5740-7be2-4459-8e8b-91bb8c163851` | completed, exit 0 |
| `20261025-052351` | admin-web | `50871b63-70b9-4ef8-baf6-f941a63c03c5` | completed, exit 0 |
| `20261025-052351` | ops-qa | `1d6c2986-a5a7-426b-aa04-df4a59bd171b` | completed, exit 0 |
| `20261025-060338` | integration-final | `b8a5d5c1-f4c4-4b69-87a5-825c25998425` | completed, exit 0 |

`integration-final`의 원래 필수 명령 `python scripts/record-native-demo.py`는 2026-10-25 10:18:31 UTC에 exit 0으로 종료됐다. 검사 전후 트리 해시도 같다. OMA finish는 10:22:29.754 UTC에 completed·exit 0으로 종료됐다. 최종 CI는 root가 별도 release evidence에 기록하며 이 문서에서 CI 성공을 대신 선언하지 않는다. 기존 v4의 terminal 상태는 복구된 것으로 표시하지 않는다.

## 검증의 한계

- 차량·내비·예약·결제는 데모 환경이다. 실제 SDK artifact의 컴파일 결과와 실제 차량 연결·명령 결과는 구분한다. 외부 캠핑장·날씨 공급자 키는 설정하지 않아 녹화 데이터도 데모로 표시했다.
- 실내 UI는 원본 SDK 제어 영역과 관측값을 보존하지만 실제 차종·트림 프로필·실내 형상은 통합하지 않았다. 모의 앞·뒤 영역으로 실제 좌석 수나 구조를 추정하지 않는다.
- 22sp·28sp 설정과 화면 검토만으로 Android 실제 글자 높이 18px, 차량의 중요 정보 5mm·보조 정보 3mm, 모든 배경의 4.5:1 대비 또는 입력 후 0.25초 반응을 충족했다고 판단하지 않는다.
- 실제 OEM 호스트의 `CarUxRestrictions` 전환, 주차 신호·차량 제어, 물리 분할 화면, 전체 접근성과 클라우드 관측성 수신은 별도 검증 대상이다.

파일 해시, 개별 화면 검토자, 제외한 캡처와 실제 검사 목록은 [동일 이름의 JSON 기록](pleos-design-runtime.json)에 정리한다. 각 검사의 완료 범위와 전체 프로젝트의 완료 여부를 구분한다.
