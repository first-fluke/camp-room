# Camp Room 구현 계획

세부 요구사항은 ../../PRD.md와 ../../FUNCTIONAL_SPEC.md. 먼저 backend/frontend를 독립 경로에 병렬 할당하고 Kotlin host 경계는 동시 실행 슬롯이 생기는 즉시 수행한다. 최종 통합본에 QA와 실제 브라우저 녹화를 수행한다. 계약은 ../contracts/camp-room-api.md. OMA 세션 20261025-214500.

- backend: 영속 API·GoCamping/KMA 서버 어댑터·주문/배송 검증
- frontend: 세 주 메뉴·차박 공간·준비물/주문 UI·접근성
- mobile: Kotlin Pleos 호스트 경계·정확한 SDK 선언 검증
- qa: 실제 브라우저 핵심 여정·영상·결과 보고
- refine: 오류 은폐·중복 검토 후 필요한 구조 개선만 수행
