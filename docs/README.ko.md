# 캠프룸 문서

이 저장소의 한국어 시작 문서는 [루트 README](../README.md)다.

| 필요한 내용 | 문서 |
| --- | --- |
| 서비스 목표와 사용자 | [제품 요구사항](PRD.md) |
| 화면과 기능 동작 | [기능명세서](FUNCTIONAL_SPEC.md) |
| 실행·빌드·검증 | [실행 안내](USAGE.ko.md) |
| 서버 구성 | [서버 구조도](architecture/SERVER_ARCHITECTURE.md) |
| 데이터 관계와 키 | [DB ERD](database/ERD.md) |
| 관리자 인증과 비밀값 | [인증 문서](AUTH.ko.md) |
| 기술 선택 이유 | [설계 선택](WHY.ko.md) |
| 외부 데이터 연결 | [연동 안내](INTEGRATIONS.md) |

캠프룸은 `first-fluke/fullstack-starter`에서 시작했으며 현재 구성은 차량용 Kotlin 앱, FastAPI 서버, 관리자 웹, 캐시 갱신 워커다. 다른 언어로 남아 있는 starter 문서는 원본 템플릿의 참고 자료이며 현재 앱의 기능·명령·인증 방식은 위 문서를 기준으로 한다.
