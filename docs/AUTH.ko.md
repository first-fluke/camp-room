# 인증과 비밀값

현재 관리자 웹은 서버에 설정한 관리자 비밀번호로 로그인한다. 일반 차량 앱은 서버가 선택한 데모 프로필을 사용한다. DB의 프로필 키가 사용자 인증 기능을 뜻하지는 않는다.

## 관리자 요청

브라우저의 로그인 요청을 Next.js 서버가 확인한다. 성공하면 `CAMP_ADMIN_SESSION_SECRET`으로 서명한 8시간 세션을 `HttpOnly`, `SameSite=Strict` 쿠키에 담는다. HTTPS에서는 `Secure` 속성도 사용한다.

관리자 화면의 조회·수정 요청은 BFF를 거친다. BFF는 세션을 검사하고 `CAMP_ADMIN_TOKEN`을 Bearer 헤더에 넣어 FastAPI의 `/api/v1/admin` 경로를 호출한다. 브라우저 JavaScript에는 API 토큰을 보내지 않는다. 변경 요청에서는 요청 출처도 확인한다.

로그인 실패 횟수 제한은 현재 프로세스 안에서 관리한다. 관리자 인스턴스를 여러 개 운영하거나 일반 사용자 로그인을 도입할 때는 계정 저장소와 공유된 인증 정책을 별도로 설계해야 한다.

## 서버 설정

| 설정 | 용도 |
| --- | --- |
| `CAMP_ADMIN_PASSWORD` | 관리자 로그인 비밀번호, 최소 12자 |
| `CAMP_ADMIN_SESSION_SECRET` | 세션 서명 키, 최소 32자 |
| `CAMP_ADMIN_TOKEN` | BFF·워커가 관리 API를 호출할 때 사용하는 서버 토큰 |
| `TURSO_DATABASE_URL`, `TURSO_AUTH_TOKEN` | API 서버의 Turso 연결 |
| `GOCAMPING_SERVICE_KEY`, `KMA_API_HUB_KEY`, `KMA_SERVICE_KEY` | 외부 데이터 공급자 인증 |

실제 값은 환경변수 또는 Git에서 제외한 `.env`에 저장한다. `.env.example`에는 항목과 비어 있는 값만 둔다. 로그, 문서, 영상, Android APK와 브라우저 번들에 비밀값을 넣지 않는다.

## 프로필과 저장 범위

`CAMP_DB_NAMESPACE`는 저장 범위인 `scope_id`, `CAMP_PROFILE_ID`는 그 안의 프로필을 선택한다. 복합 외래 키는 다른 범위나 프로필의 관계가 섞이는 쓰기를 막는다. 클라이언트가 이 키를 안다는 이유로 접근을 허용하는 인증 장치는 아니다.

관리 API는 토큰이 없거나 다르면 요청을 거절한다. 앱용 API를 여러 사용자에게 제공할 때는 로그인·권한 검사와 서버 측 프로필 연결을 추가해야 한다. 현재 데모 API의 여행·장바구니·주문을 개인 계정으로 보호한다고 표현하지 않는다.

## 구현과 검증

세션 검증은 [admin-session.mjs](../apps/admin/src/lib/admin-session.mjs), BFF 인증은 [admin-server.ts](../apps/admin/src/lib/admin-server.ts), 관리 API 인증은 [main.py](../apps/api/app/main.py)에 있다. 관리자 세션 테스트는 `mise run //apps/admin:test`, API 인증 검증은 `mise run //apps/api:test`로 실행한다.
