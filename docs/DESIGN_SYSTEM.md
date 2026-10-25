# Pleos 디자인 가이드 적용

캠프룸은 Pleos의 공개 디자인 가이드를 참고해 Compose 테마와 공통 컴포넌트를 구성한다. 구현은 [CampTheme.kt](../apps/pleos/app/src/main/java/camp/room/app/CampTheme.kt)에 모여 있다. 공식 Kotlin UI 라이브러리를 사용했다고 표현하지 않는다.

## 적용한 기준

| 항목 | 캠프룸 구현 |
| --- | --- |
| 글꼴 | 한국어·영어를 지원하는 Asta Sans, 앱에 폰트와 OFL 라이선스 포함 |
| 주간·야간 | 시스템 다크 모드에 맞춘 의미별 색상 팔레트 |
| 글자 크기 | 명시적인 보조 글자 최소 12sp, 본문 14~18sp, 주요 동작 18sp |
| 공통 버튼 | 주요 동작 64dp, 보조 동작 56dp, 아이콘 영역 48dp |
| 상태 | 눌림·비활성·처리 중 표시, 공통 처리 중 표시와 햅틱 피드백 |
| 짧은 텍스트 | 공통 텍스트 표시를 최대 3줄로 제한 |

색상 토큰의 주요 조합을 계산한 대비는 5.34~12.85:1이다. 이 수치는 선택한 토큰 조합의 검사 결과이며 모든 화면·이미지·실제 차량 디스플레이의 대비를 인증한 결과는 아니다.

Pleos 가이드의 5mm·3mm는 중요한 정보와 보조 정보의 **텍스트 높이**에 대한 기준이다. 앱의 dp·sp만으로 실제 디스플레이의 물리적 크기를 확인했다고 판단하지 않는다. 버튼 예시 이미지의 px 치수를 공통 dp 규칙으로 간주하지 않고 앱의 컴포넌트 크기를 별도로 정했다.

입력 후 반응이 늦어질 때는 처리 중임을 즉시 표시한다. 가이드에 나온 0.25초는 사용자 입력에 대한 UI 피드백 기준이며 Vehicle·NaviHelper 등 SDK 호출이 그 시간 안에 완료된다는 약속이 아니다.

## 공식 참고 자료

- [Pleos Connect UI Design Guide](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)
- [Typography](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography)
- [Basic button](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button)

소스 토큰 검사, 실제 APK 화면 확인, 차량 디스플레이의 물리적 가독성 확인은 별도 단계로 검증한다. 적용 범위와 아직 관측하지 않은 결과를 구분해 기록한다.
