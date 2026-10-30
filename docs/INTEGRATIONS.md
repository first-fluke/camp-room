# 외부 데이터 연결

캠프룸은 캠핑장 정보·날씨·차량 관측을 같은 여행에 연결한다. 공개 API는 `https://camp-room-api.firstfluke.com/api/v1`이다.

## 연결별 역할

| 연결 | 제품에서 제공하는 정보 |
| --- | --- |
| 고캠핑 | 캠핑장 이름·주소·좌표·시설·사진과 지역·시설 검색 |
| 기상청 | 캠핑장 위치와 체류 일정에 맞춘 시간별 예보 |
| Turso | 캠핑장 캐시·현재 여행·상품 시뮬레이션과 갱신 이력 |
| Pleos SDK | 차량 내 배터리·온도·위치 관측과 경로 미리보기 |
| 상품·배송 시뮬레이션 | 준비물 선택·장바구니·배송 조건 확인과 데모 주문 저장 |

고캠핑·기상청 데이터에는 출처와 갱신 시각을 함께 보관한다. 서버는 실제 공급자와 개발용 공급자의 모드를 관리하고 worker가 공유 캐시를 갱신한다.

공급자 인증과 Turso 연결은 서버의 비공개 설정으로 관리하며, 차량 관측은 앱의 Pleos SDK 어댑터가 담당한다. 로컬 개발에는 같은 스키마의 SQLite를 사용한다.

## 자료와 실행 안내

- [한국관광공사 고캠핑 정보 조회서비스](https://www.data.go.kr/data/15101933/openapi.do)
- [기상청 API허브 단기예보](https://apihub.kma.go.kr/apiList.do?seqApi=10&seqApiSub=286)
- [실행 방법과 환경설정](../README.md)
- [서버 구성](architecture/SERVER_ARCHITECTURE.md) · [서버 운영](operations/DEPLOYMENT.md)
