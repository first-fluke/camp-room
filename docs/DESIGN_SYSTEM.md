# Pleos 디자인 가이드 적용

캠프룸은 Compose·Material3에 Pleos 공개 디자인 가이드를 적용한다. 큰 글씨와 터치 영역, 주야간 테마, 화면 폭에 맞춘 배치로 차량 안에서 여행 정보를 읽고 준비할 수 있게 한다.

## 화면 기준

| 항목 | 적용 내용 |
| --- | --- |
| 글꼴 | 한국어·영어 Asta Sans, OFL 라이선스 포함 |
| 글자 크기 | 공통 테마와 확인한 화면 7개의 명시적 글자 최소 22sp, 줄 높이 최소 28sp |
| 버튼 | 주요 동작 64dp, 보조 동작 56dp, 아이콘 영역 48dp |
| 색상 | 시스템 주야간 설정에 따른 의미별 팔레트 |
| 배치 | 화면 폭에 따른 줄바꿈·세로 배치·스크롤 |
| 조작 피드백 | 눌림·처리 중 상태와 햅틱, 음성 창 닫기와 인식 취소 연결 |

## 차량 화면

차량 관측은 기능별 위치·값·상태로 표시하며 현재 온도와 목표 온도를 구분한다. SDK가 제공한 원본 영역을 사용하고 CarUx가 허용한 전면 실행 상태에서 앱 조작과 관측을 시작한다. 차량 설정에는 기능별 권한·지원 범위와 확인된 주차 상태를 조건으로 사용한다.

화면과 입력 흐름은 [기능명세서](FUNCTIONAL_SPEC.md)에 정리했다.

## 자산과 출처

기본 조작·차량 기능에는 Pleos 벡터, 캠핑·위치·배송에는 Phosphor 아이콘을 사용한다. 런처는 Phosphor `tree-evergreen`을 전경으로 둔 Adaptive Icon이다.

기준 자료는 Pleos [UI Design Guide](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro), [Display Guide](https://document.pleos.ai/img/Pleos_Connect_display_guide_215.fig), [Design Assets](https://document.pleos.ai/img/Pleos_Connect_design_assets_215.fig)다.

원본과 변환 정보는 [Pleos 출처](../apps/pleos/app/src/main/assets/icon-provenance/pleos.json), [Phosphor 출처](../apps/pleos/app/src/main/assets/icon-provenance/phosphor.json)에 연결한다. Pleos/HMG 권리 표기와 [Phosphor MIT 라이선스](https://github.com/phosphor-icons/core/blob/2b75f3ad12b420c9504ef05df8d2564a28f8500e/LICENSE)를 함께 보관한다.
