# 외부 데이터 및 계정 연결

## 현재 연결 상태

Android demo flavor 시연은 자격증명 없이 재현 가능한 데모 모드다. 실제 캠핑장/기상 HTTP 어댑터는 서버에 별도로 구현되며 발급한 키를 루트 `.env`에 넣어 활성화한다. 실제 토큰을 발급받거나 실시간 공급자 호출이 성공했다고 주장하지 않는다. 키 발급 및 실제 호출 결과는 `docs/verification/`에 검증이 끝난 범위만 기록한다.

Aside CLI와 MCP 설치·호출 경로는 준비되었다. 사용자 계정으로 가입하거나 토큰을 받으려면 인증된 Aside 브라우저 프로필이 필요하다. 이번 세션에서 확인한 상태는 실행 중 로컬 프로필/인증된 원격 프로필이 없고, 계정 선택 및 사용자 인증이 필요한 상태다. 이 상태를 앱 개발의 선행 조건으로 만들지 않는다.

## 서버 환경변수

| 이름 | 사용 | 비밀 여부 |
|---|---|---|
| CAMP_DATA_MODE | demo / auto / live | 공개 설정 |
| GOCAMPING_SERVICE_KEY | data.go.kr 고캠핑 인증키 | 서버 비밀 |
| KMA_API_HUB_KEY | 기상청 API허브 authKey | 서버 비밀 |
| KMA_SERVICE_KEY | data.go.kr 기상 단기예보 서비스키 대안 | 서버 비밀 |
| CAMP_DB_PATH | 로컬 상태 저장 파일 경로 | 로컬 설정 |
| campApiBaseUrl (Gradle property) | Android backend origin including /api/v1 | 빌드 공개 설정 |

실제 `.env`는 Git에서 제외한다. `.env.example`에는 값이 없는 예시만 제공한다. 비밀을 NEXT_PUBLIC 변수나 UI, 오류 URL, 요청 로그, 문서, 영상에 넣지 않는다. 키가 잘못되면 인증 실패/연결 미완료로 표현하며 데모가 실시간 결과로 보이게 바꾸지 않는다.

## 공급자 별 책임

### 고캠핑

[한국관광공사 고캠핑 정보 조회서비스](https://www.data.go.kr/data/15101933/openapi.do)는 장소·위치·시설 정보를 공통 Campsite 모델로 변환하는 공급자다. 대표 조회는 GoCamping basedList다. 캠핑장 ID를 예약 공급자 ID로 임의 대체하지 않는다. 가격, 차박 숙박 허용, 택배 수령 조건 등 실제 응답에 없는 정보는 null/확인 필요이며 가용성은 unknown이다. 가용 좌석이나 실제 예약 건수를 생성하지 않는다.

### 기상청

[기상청 API허브 단기예보](https://apihub.kma.go.kr/apiList.do?seqApi=10&seqApiSub=286)의 `https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getVilageFcst`를 authKey 및 base_date/base_time/nx/ny와 함께 호출한다. 캠핑장 좌표를 격자 좌표로 변환하고 실제 제공된 시간대만 반환한다. 예약일이 예보 제공 기간을 넘으면 장기 예보를 만들지 않고 미제공을 표시한다. KMA_SERVICE_KEY는 data.go.kr route를 위한 대안이다.

### 예약·결제·배송

고캠핑·기상청 가입이 예약 재고·결제·택배 API 권한을 제공하는 것은 아니다. 실제 예약은 해당 사업자의 계약된 연동 또는 예약 화면 연결이 필요하다. 현재 주문은 `DEMO-` 번호의 저장된 데모 주문이며 실제 카드 승인·택배 접수는 발생하지 않는다. 상품·재고·주문/배송 데이터를 연결하려면 판매처별 인증과 검증이 추가된다.

## 모드 동작

- demo: 데모 공급자/시뮬레이터로 실행하며 모든 출처에 demo를 유지한다.
- auto: 구성된 실제 공급자를 요청한다. 실패 또는 미구성인 경우에만 명시적인 데모 fallback과 원인을 제공한다.
- live: 실제 제공 결과 또는 미연결/오류 상태만 반환한다. 가짜 성공 응답을 만들지 않는다.

예약 inventory와 차량 제어/결제는 live 장소/기상 데이터와 별도다. 일부 공급자만 연결되면 화면은 혼합 출처를 보여주고 각 데이터의 모드·확인 시각을 유지한다.

## 자격증명 발급 후 검증 절차

1. 인증된 Aside 프로필에서 사용자 지정 계정으로 각 공급자의 이용 신청/승인 상태를 확인한다.
2. 발급키를 서버 환경변수로 저장하고 실제 값은 채팅·영상에 노출하지 않는다.
3. CAMP_DATA_MODE=live로 서버를 재시작한다.
4. `/api/v1/bootstrap`의 providers와 장소/날씨 source를 확인한다. 비밀 값은 응답에 없어야 한다.
5. 인증 실패, 타임아웃, 예보 기간 밖, 누락 시설/예약 재고를 확인한다.
6. 성공한 공급자·시각·검증 범위만 기록하고 시연 녹화는 비밀이 없는 UI로 진행한다.
