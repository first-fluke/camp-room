# Camp Room 운영 관리 웹

Camp Room의 Kotlin/Pleos 앱과 별도로 사용하는 실제 Next.js 운영 콘솔입니다. 화면은 관리 API의 응답을 사용하며 정적 목록이나 가짜 저장 동작을 사용하지 않습니다.

## 실행

저장소 루트에서 다음 명령을 실행합니다.

```sh
npm install
npm --workspace admin run dev
```

기본 주소는 `http://127.0.0.1:3000`입니다. 루트 `.env`에서 서버 전용 설정을 읽습니다. 환경변수를 변경한 뒤에는 개발 서버를 다시 시작합니다.

| 환경변수 | 용도 |
|---|---|
| `CAMP_API_BASE_URL` | FastAPI origin. 생략 시 `http://127.0.0.1:8000`. `/api/v1`을 포함해도 정상화합니다. |
| `CAMP_ADMIN_TOKEN` | 관리 API에만 전달하는 서버 측 Bearer token |
| `CAMP_ADMIN_PASSWORD` | 관리자 비밀번호. 12자 이상 |
| `CAMP_ADMIN_SESSION_SECRET` | 세션 서명 키. 32자 이상 |

실제 값은 코드, 공개 예시, 브라우저 bundle에 넣지 않습니다. 관리자 비밀번호나 API token을 확인 화면에 표시하지 않습니다.

## 제공 기능

- 관리자 로그인·로그아웃, HttpOnly/SameSite Strict 서명 cookie, 8시간 세션
- 실제 데이터베이스 종류·namespace, 공급자 설정 여부·오류 상태, 캠핑장·주문 수 조회
- 캠핑장 택배 수령 허용, 수령 위치와 안내 문구의 실제 API 저장
- 준비물 주문의 접수·준비·수령 준비·취소 상태와 운영 메모의 실제 API 저장
- 데모 주문과 실제 결제·택배 접수의 명확한 구분

브라우저는 같은 origin의 BFF만 요청합니다. BFF가 서버에서 관리 token을 붙여 FastAPI를 호출합니다. 상태 변경은 같은 origin을 검사하고, upstream 경로의 ID를 검증합니다. 로그인 오류는 비밀번호 값을 출력하지 않습니다.

## HTTP 경로

- `POST /api/auth/login`: `{password}`. 성공 시 `camp_admin_session` cookie
- `POST /api/auth/logout`: 현재 브라우저 cookie 만료
- `GET /api/auth/session`: `{authenticated, configured}`
- `GET /api/admin/overview`
- `PATCH /api/admin/campsites/{id}/receiving`: `{acceptsParcels, location, instructions}`
- `PATCH /api/admin/orders/{id}`: `{fulfillmentStatus, adminNote}`

업무 API의 필드와 저장 책임은 `docs/plans/contracts/admin-api.md`를 따릅니다. 주문의 기존 `status: demo_confirmed`는 그대로 두고 `fulfillment` 운영 기록만 변경합니다.

## 검증

```sh
npm --workspace admin run typecheck
npm --workspace admin run test
```

보안 테스트는 서명 변조·만료·잘못된 키·미래 시각 세션, 잘못된 비밀번호, 요청 origin, 경로 이탈을 검증합니다. 실제 cookie/BFF 및 저장·복구 통합 검증은 저장소의 `scripts/verify-admin.py`에서 수행합니다.
