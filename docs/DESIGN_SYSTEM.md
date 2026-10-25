# Pleos 디자인 가이드 적용

캠프룸은 Compose·Material3로 UI를 구현하고 Pleos의 공개 디자인 가이드를 테마와 공통 컴포넌트에 적용한다. 아래 내용은 소스 반영 범위다. 새 APK의 화면·입력 검사와 실제 차량 검증 결과는 [디자인 감사 기록](verification/pleos-design-audit.md)에서 구분한다.

## 공식 디자인 자산과 런타임 UI

Pleos는 [UI Design Guide](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)에서 [display guide](https://document.pleos.ai/img/Pleos_Connect_display_guide_215.fig)와 [design assets](https://document.pleos.ai/img/Pleos_Connect_design_assets_215.fig)를 Figma 파일로 제공한다. 실제 파일의 Foundation·Components 페이지와 `ic_mic`, `ic_close`, `ic_arrow`, `ic_sound`, `ic_check`, `ic_eye`, `ic_dropdown`, `ic_charging`, `ic_seat_heat_3` 아이콘 계열을 확인했다. design assets의 `SYMBOL` 567개는 변형을 포함한 전체 개수이며 고유 아이콘 종수가 아니다.

2026-10-25에 확인한 공개 SDK 문서, 공식 GitHub 샘플, 공개 Maven 저장소(14개 좌표·36개 버전)에서는 별도 Pleos Kotlin/Compose/View UI 라이브러리를 찾지 못했다. 공식 Vehicle 샘플의 UI 의존성은 AndroidX Compose UI·Material3다. 확인 범위와 다운로드 파일의 해시·메타데이터는 [자산 검사 기록](verification/pleos-design-assets.json)에 남겼다.

현재 소스에는 **Pleos 공식 벡터 19개와 Phosphor SVG 변형 37개**를 반영했다. 기본 조작·차량 기능은 공식 벡터를 우선 사용하며 캠핑·위치·배송 범주는 Phosphor로 보완한다. Phosphor 구성은 regular 24개, fill 2개, duotone 11개다. 원본 파일·변환 근거는 앱에 포함된 [Pleos 출처 기록](../apps/pleos/app/src/main/assets/icon-provenance/pleos.json)과 [Phosphor 출처 기록](../apps/pleos/app/src/main/assets/icon-provenance/phosphor.json)에서 확인할 수 있다. Pleos/HMG 디자인 자산의 권리 표기와 [Phosphor의 MIT 라이선스](https://github.com/phosphor-icons/core/blob/2b75f3ad12b420c9504ef05df8d2564a28f8500e/LICENSE)는 별도로 유지한다.

런처에는 배경·전경을 나눈 [Adaptive Icon](../apps/pleos/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml)을 연결했다. 전경은 Phosphor의 `tree-evergreen` 자산이며 Pleos의 공식 앱 로고로 표시하지 않는다. 마스크별 표시 결과는 새 APK에서 확인해야 한다.

## 소스에 적용한 기준

| 항목 | 현재 구현과 확인 범위 |
| --- | --- |
| 글꼴 | 한국어·영어용 Asta Sans와 OFL 라이선스를 앱에 포함 |
| 주간·야간 | 시스템 다크 모드에 따라 의미별 색상 팔레트 전환 |
| 글자 크기 | 공통 테마와 확인한 7개 화면 파일의 명시적 `fontSize` 최소 22sp. 명시적 `lineHeight` 최소 28sp |
| 공통 버튼 | 주요 동작 64dp, 보조 동작 56dp, 아이콘 영역 48dp. 앱에서 정한 크기이며 공식 도면 px의 단순 환산값이 아님 |
| 방향·창 크기 | Manifest의 가로 방향 고정 제거. 좁은 화면에서 줄바꿈·세로 배치·스크롤을 사용하도록 수정 |
| 조작 상태 | 눌림·비활성·처리 중 표시와 햅틱 피드백. 음성 창 닫기는 해당 인식 작업 취소와 연결 |
| 입력 일관성 | 견적을 요청 당시 장바구니·배송 정보와 대조. 처리 중 입력·장면 선택 변경 제한 |

글꼴·색·공통 크기는 [CampTheme.kt](../apps/pleos/app/src/main/java/camp/room/app/CampTheme.kt), 화면 구성은 [MainActivity.kt](../apps/pleos/app/src/main/java/camp/room/app/MainActivity.kt)와 각 화면 파일에 구현되어 있다. 기존의 의미별 색 조합 5.34~12.85:1은 선택한 토큰에 대한 기록이다. 후속 감사에서 발견한 상품 그림·컬렉션 배경의 대비는 소스에서 수정했으며, 모든 화면의 대비를 통과한 것으로 처리하지 않는다.

[Typography](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography)의 12sp 권장 하한과 실제 글자 높이 18px, 소개 가이드의 중요 정보 5mm·보조 정보 3mm는 각각 확인해야 한다. 22sp 적용만으로 Android 렌더의 18px 또는 차량 디스플레이의 물리 높이를 충족했다고 판단하지 않는다.

입력 후 첫 반응이 늦어질 때는 처리 중임을 표시한다. 가이드의 0.25초는 사용자 입력에 대한 UI 피드백 기준이다. SDK 호출 완료 시간과 구별하며, 이 문서에서는 실제 반응 시간을 측정했다고 주장하지 않는다.

## 주행 제한과 차량 제어

Pleos 빌드는 [CampUxMonitor.kt](../apps/pleos/app/src/pleos/java/camp/room/app/CampUxMonitor.kt)에서 `CarUxRestrictionsManager`의 현재 상태와 변경을 수신한다. 앱은 distraction optimization을 선언하지 않는다. 제한 상태이거나 연결·권한·응답 문제로 상태를 모르면 일반 화면 대신 제한 화면을 보여준다. Activity가 비활성화될 때도 일반 조작을 닫는다. 데모 빌드는 제한 없는 상태를 명시적으로 모의한다.

이 UI 제한은 차량 제어의 주차 확인과 별개다. UI를 열 수 있다는 이유로 주차 상태나 차량 명령 권한을 추정하지 않는다. 제어 어댑터는 기능별 권한·허용 값·원본 영역과 신뢰할 수 있는 주차 상태를 따로 확인한다. 실제 차량의 제한 전환과 명령 결과는 별도 검증 대상이다. 근거는 [공식 주행 제한 개발 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)다.

## 실내 화면이 표현하는 정보

실내 화면은 확인된 **기능별 제어 영역과 관측값**을 표시한다. [제공 SDK 명세](product/pleos-sdk.spec.jsonl)의 `SeatArea`는 여러 위치를 묶을 수 있으며, `ROW_1_LEFT` 같은 위치는 좌석 수나 운전석 여부를 뜻하지 않는다. `CarInfo.getVINInfo`·`getCRNInfo`는 식별 문자열을 읽는 API다. 제공 명세에는 정확한 차종·트림·실내 형상을 반환하는 API가 없다.

[CabinTopology.kt](../apps/pleos/app/src/main/java/camp/room/app/CabinTopology.kt)와 [Pleos 연결부](../apps/pleos/app/src/pleos/java/camp/room/app/PlatformBridge.kt)는 SDK가 반환한 영역 객체와 구성원 순서·중복을 보존한다. 같은 구성원이 반복되어도 기능별 식별자로 구분하며, 조회·명령은 원본 영역을 사용한다. 현재 온도와 목표 온도도 각각의 capability와 조회 결과로 다룬다. 앞·뒤 요청은 해당하는 원본 영역에 각각 전달하고, 경계를 걸치는 묶음이나 위치를 알 수 없는 영역은 임의로 나누어 제어하지 않는다.

[실내 화면](../apps/pleos/app/src/main/java/camp/room/app/CampCabinScreen.kt)은 행·왼쪽·가운데·오른쪽 위치와 기능별 값을 보여준다. 고정된 차량 실루엣이나 좌석 수를 만들어내지 않으며, 확인하지 못한 영역·값은 미확인으로 남긴다. 데모의 앞·뒤 영역은 `demo_synthetic`으로 구분한다. 현재 앱은 VIN·CRN·차종 정보를 수집하지 않는다. 차종별 실내 그림은 검증된 모델·트림 자료와 연결 규칙을 확보한 뒤 도입할 확장 범위다.

## 후속 검증

소스 반영과 실제 가이드 준수는 구분한다. 새 APK의 세로·분할 창, 주·야간 및 한국어·영어 화면, 입력 상태와 주행 제한 전환을 먼저 확인해야 한다. 실제 텍스트 높이·터치 영역·입력 피드백 시간과 차량의 물리 치수는 측정 증거가 필요하다. 수정 전 판정과 남은 항목은 [감사 기록](verification/pleos-design-audit.md)에 보존한다.
