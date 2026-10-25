# Pleos 디자인 가이드 감사와 후속 수정

## 범위와 현재 판정

이 기록은 2026-10-25에 확인한 공식 가이드, 수정 전 소스·이전 APK 화면, 이후 소스 변경을 구분한다. **전체 가이드 준수 완료 판정은 내리지 않았다.** 후속 소스 반영에는 새 APK 실행·스크린샷·차량 실측 결과가 포함되지 않는다. 원래 요구사항 25개의 대조표와 출처·해시는 [기계 판독 기록](pleos-design-audit.json)에 보존한다.

수정 전 감사는 08:56~09:03 UTC에 진행했다. 판정은 **PASS 6개, PARTIAL 10개, FAIL 3개, NOT_OBSERVED 6개**였다. 이는 당시 증거의 판정이며 이후 변경을 자동으로 통과 처리한 수치가 아니다. `FAIL`은 가로 방향 고정(D01), Adaptive Icon 미연결(D04), 기존 표시 구성에서 작은 글자의 18px 기준 미달(F03)이었다.

당시 본 화면은 `native-discover.png`, `native-dark.png`, `native-english.png`의 1280×800 이미지다. 이전 APK SHA-256은 `131e1b64e3e6752b26b42ee4db2e75ec72dbcd9e26d6e7f14ca6e8fe6f2c571a`, 소스 커밋은 `29430437acbf5a6cc1cb8a500718fa8be6daeb1d`다. 이 화면으로 새 아이콘·22sp 글자·영역 기반 실내 UI의 실행 결과를 입증하지 않는다.

## 요구사항별 후속 상태

아래의 ‘소스 반영’은 구현을 읽어 확인했다는 뜻이다. 해당 항목의 런타임 판정을 PASS로 바꾸는 근거가 아니다. Kotlin 경로는 `apps/pleos/app/src/` 기준이며 세부 해시는 JSON에 기록한다.

| 요구사항·공식 근거 | 확인한 소스 변경 | 현재 상태와 남은 확인 |
| --- | --- | --- |
| D01·D02 — [Layout][layout]·[Window][window] | `main/AndroidManifest.xml`의 방향 고정 제거. `MainActivity.kt`와 화면 파일에 줄바꿈·세로 배치·스크롤 보강 | 소스 반영. 세로·분할 창, IME·시스템 패널 아래의 핵심 동작 접근 확인 필요 |
| D04 — [Adaptive Icon][appicon] | Manifest의 `icon`·`roundIcon`과 전경·배경 리소스 연결. Phosphor `tree-evergreen` 사용 | 소스 반영. 런처 마스크·safe area의 실제 표시 미관찰 |
| F02·F03·F04 — [Typography][typography]·[텍스트 높이][intro] | `CampTheme.kt`와 확인한 7개 화면의 명시적 글자 최소 22sp, 명시적 줄 높이 최소 28sp | 소스 반영. Android 실제 글자 높이 18px, 차량의 중요 정보 5mm·보조 정보 3mm는 미측정 |
| F05·F06 — [대비·주야간][intro]·[Colors][colors] | 상품 그림·컬렉션 배경 색 조합 수정. 주간·야간 팔레트 유지. 기존 차량 실루엣은 제어 영역 카드로 교체 | 소스 반영. 모든 텍스트·벡터·합성 배경의 4.5:1 대조와 새 주·야간 화면 확인 필요 |
| F07·F08 — [Iconography][icons]·[Icon button][iconbutton] | 공식 Pleos 벡터 19개, Phosphor 변형 37개와 각각의 출처·권리 표기 포함 | 소스 반영. 표시 크기·정렬·선택 상태·접근성 이름은 새 APK에서 확인 필요 |
| C01·C02·C08 — [Basic button][button]·[Controllers][controllers] | 공통 64/56/48dp, Switch·Slider 최소 48dp 영역, busy 중 장면 선택 잠금 | 소스 반영. 실제 터치 경계, 기능별 capability·끝값·증분·차단 이유의 UI 반영은 후속 확인 |
| C03 — [입력 피드백][intro] | 공통 로더·비활성 상태·햅틱 유지 | 구현 구조 확인. 입력 후 0.25초 내 첫 피드백은 미측정이며 SDK 완료 시간과 구분 |
| C04·C05·C06 — [텍스트][intro]·[Navigation][nav]·[Popup][popup] | 메뉴·칩 줄바꿈, 좁은 수동 제어의 세로 배치, 팝업 스크롤 보강 | 소스 반영. 긴 번역·외부 데이터·큰 글자·키보드에서 잘림과 확인/닫기 접근 미검증 |
| I-01·I-02·I-03 — [상태 피드백][button]·[Popup][popup] | `CampViewModel.kt`·`CampDialogs.kt`·`CampVoiceDialog.kt`에 인식 Job 취소, 견적 입력 snapshot 대조, 처리 중 입력·장면 선택 제한 | 소스 반영. 취소·뒤늦은 응답·중복 입력에 대한 새 실행 결과는 이 기록에 포함하지 않음 |
| S01·I-05 — [주행 제한][driving] | Pleos `CampUxMonitor.kt`의 `CarUxRestrictionsManager` 구독, `CampUxState.kt`·`MainActivity.kt`의 화면 및 동작 제한 | 소스 반영. 미확인·연결 해제도 차단. 실제 호스트의 제한 전환·복귀는 미관찰 |
| 실내 정보 정확성 — [제공 SDK 명세](../product/pleos-sdk.spec.jsonl) | `CabinTopology.kt`·Pleos `PlatformBridge.kt`에서 원본 제어 영역 보존. `CampCabinScreen.kt`는 위치·기능별 값 표시 | 소스 반영. 실제 차량의 독립 영역·묶음 영역·미확인 값과 명령 결과 검증 필요 |

## 차량 정보와 실내 표현의 경계

SDK 명세와 실제 AAR의 타입을 대조했다. 명세 SHA-256은 `e877bd335709324b5139bd4e3b655648c9cc93d19d5c4a3835585a376628be96`다. VIN을 실제로 읽거나 차종을 추정한 검사는 하지 않았다.

| SDK가 제공하는 정보 | 표현할 수 있는 내용 | 이 정보만으로 확정할 수 없는 내용 |
| --- | --- | --- |
| `CarInfo.getVINInfo`·`getCRNInfo`의 `String?` | 권한·capability를 확인한 식별 문자열 조회 | 정확한 모델·연식·트림·실내 형상. 현재 앱은 이 식별값을 수집하지 않음 |
| `SeatArea(vararg SeatValue)`와 `values: Array<out SeatValue>` | 하나의 제어 영역에 포함된 위치. 여러 위치가 한 영역일 수 있음 | 실제 설치 좌석 수, 개별 좌석마다 독립 제어 가능하다는 판단 |
| 기능별 capability의 `List<SeatArea>` 또는 영역별 bounds 맵 | 조회·제어 가능 여부와 지원 영역·범위·증분 | 차체·좌석의 크기·곡면·배치 좌표. 응답 실패만으로 좌석 부재를 판정할 수도 없음 |

`SeatValue`의 1~3행 × 왼쪽·가운데·오른쪽 9개 값은 가능한 위치 식별자다. 9인승을 뜻하지 않으며, LEFT·RIGHT를 운전석·동승석으로 바꾸지 않는다. 구성원의 순서·중복과 SDK 원본 영역 객체를 보존하고, 같은 구성원이 반복되는 항목도 별도 식별자로 유지한다. 앞·뒤 요약은 관측값이 모두 일치할 때만 표시한다. 경계를 걸친 영역을 임의로 나누어 명령하지 않는다.

현재 온도와 목표 온도는 서로 다른 capability·getter로 조회한다. 실내 UI는 확인한 위치·기능·값을 표시하고, 데모 영역은 `demo_synthetic`으로 구분한다. 차종에 맞는 그림은 검증된 모델·트림별 자료를 확보한 뒤 연결할 후속 범위다. VIN만으로 실내 구조를 결정하는 기능은 구현하지 않았다.

## 남은 검증

1. **새 APK 화면·입력:** 세로·분할 창, 주·야간, 한국어·영어, 큰 글자, IME·팝업, 동적 오류·긴 외부 텍스트를 확인한다. 최소 크기와 줄 제한을 지정한 것만으로 가독성 통과를 선언하지 않는다.
2. **측정:** 실제 렌더의 18px, 차량의 5mm·3mm 텍스트 높이, 모든 배경의 4.5:1 대비, 터치 경계, 입력 후 첫 피드백 시간을 확인한다. 폰트 파일에 대한 Pillow 잉크 높이 비교는 Android·실차 측정을 대신하지 않는다.
3. **제한·제어:** UX 제한 상태 수신 실패·연결 해제·Activity 전환을 확인한다. 별도의 주차 확인과 영역별 권한·허용 값도 유지하며, 차단 이유가 UI에 명확히 나타나는지 확인한다.
4. **영역별 결과:** 왼쪽·오른쪽, 2행·3행, 공유 영역, 순서·중복, 현재·목표 온도 차이, 응답 미확인을 실제 SDK 환경에서 확인한다. 새로 추가한 회귀 테스트의 실행 결과는 별도 증거로 기록한다.
5. **남은 컴포넌트:** 날짜·입력 필드의 전체 상태, Snackbar와 공식 Toast의 대응, OS 소유 패널·위젯 등은 적용되는 범위를 추가 대조한다. 사용하지 않는 컴포넌트 자체를 결함으로 보지는 않는다.

원래 감사의 수치와 파일 해시는 유지하고, 후속 런타임 결과는 새 증거에 연결해 추가한다. 이 기록을 소급해서 실행 검증 완료로 바꾸지 않는다.

[intro]: https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro
[layout]: https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout
[window]: https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window
[appicon]: https://document.pleos.ai/docs/connect/guide/docs-design/foundations/contents-creation/app-icon
[typography]: https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography
[colors]: https://document.pleos.ai/docs/connect/guide/docs-design/foundations/colors
[icons]: https://document.pleos.ai/docs/connect/guide/docs-design/foundations/contents-creation/iconography
[iconbutton]: https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/icon-button
[button]: https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button
[controllers]: https://document.pleos.ai/docs/connect/guide/docs-design/components/controllers
[nav]: https://document.pleos.ai/docs/connect/guide/docs-design/components/page-navigation
[popup]: https://document.pleos.ai/docs/connect/guide/docs-design/components/popups/system-popup
[driving]: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide
