# 캠프룸 실행 안내

## 환경 준비

Python 3.14와 uv, JDK 17, Android SDK 35를 준비한다. 관리자 화면에는 Node.js 24가 필요하다. `.env`가 없다면 루트 `.env.example`을 복사해 설정하고 이미 있다면 필요한 항목만 추가한다.

```bash
uv sync --all-packages --frozen
npm ci
```

API 주소와 토큰은 [환경변수 안내](INTEGRATIONS.md)를 따른다. 실제 키를 저장소에 커밋하지 않는다.

## API와 Android 앱

```bash
.venv/bin/python -m uvicorn app.main:app --app-dir apps/api --host 127.0.0.1 --port 8000
```

API 문서는 `http://127.0.0.1:8000/docs`, 상태 확인은 `http://127.0.0.1:8000/health`다. Turso 설정이 있으면 원격 DB를 사용한다. 녹화·테스트용 로컬 DB가 필요하면 실행할 때 `CAMP_DB_PATH`에 별도 파일을 지정한다.

별도 터미널에서 Android 앱을 빌드하고 설치한다.

```bash
bash apps/pleos/gradlew -p apps/pleos :app:assembleDemoDebug
adb install -r apps/pleos/app/build/outputs/apk/demo/debug/app-demo-debug.apk
adb shell am start -n camp.room.app.demo/camp.room.app.MainActivity
```

에뮬레이터 앱은 `http://10.0.2.2:8000/api/v1`로 연결한다. 실제 기기의 서버 주소는 Gradle 속성으로 지정한다.

```bash
bash apps/pleos/gradlew -p apps/pleos :app:assemblePleosDebug -PcampApiBaseUrl=https://YOUR_BACKEND/api/v1
```

화면 상단 언어 설정에서 시스템 언어, 한국어, 영어를 선택한다. 지역·날짜·시설을 고른 뒤 장소를 내 캠핑에 저장하면 길안내·차박·준비물 흐름으로 이어진다. 자세한 동작은 [기능명세서](FUNCTIONAL_SPEC.md)를 참고한다.

## 관리자 화면과 워커

관리자 비밀번호, 세션 서명 키와 API 관리자 토큰을 서버 환경에 설정한다. `CAMP_API_BASE_URL`은 관리자 BFF가 접속할 API 주소다.

```bash
npm run admin:dev
uv run --package camp-room-worker camp-room-worker --once
```

관리자 화면은 `http://127.0.0.1:3000`에서 연다. 공급자·DB 상태, 캠핑장의 택배 수령 조건, 데모 주문 처리 상태와 메모를 관리한다. 워커를 계속 실행하려면 `--once` 대신 `--interval 600`을 사용한다. 클라우드 스케줄러는 자동으로 생성하지 않는다.

## 계약 생성과 검증

API 모델을 바꾼 뒤에는 OpenAPI를 갱신한다. 생성된 Kotlin 파일은 직접 고치지 않는다.

```bash
.venv/bin/python apps/api/scripts/export_openapi.py
bash apps/pleos/gradlew -p apps/pleos :app:openApiGenerate
```

```bash
.venv/bin/python -m pytest apps/api/tests apps/worker/tests -q
.venv/bin/python scripts/review-database.py
.venv/bin/python apps/api/scripts/export_openapi.py --check
python apps/pleos/verify_contracts.py
bash apps/pleos/gradlew -p apps/pleos :api-client:test :app:assembleDemoDebug :app:assemblePleosDebug
npm --workspace admin run typecheck
npm --workspace admin test
```

원격 DB 검증은 서버용 Turso 설정을 읽고 독립된 검증 범위를 만든다. 검증 후 그 범위의 데이터만 정리한다.

```bash
.venv/bin/python apps/api/scripts/verify_turso.py --run-remote
```

## 시연 녹화

명시적인 `demo` 모드의 독립 DB, 실행 중인 Android 에뮬레이터와 설치한 demo APK가 필요하다. `ANDROID_HOME` 또는 `ADB_PATH`가 해당 도구를 가리켜야 한다.

```bash
python scripts/record-native-demo.py
```

스크립트는 언어 전환, 캠핑장 저장, 길안내, 차박 장면, 명령 미리보기, 상품·배송·데모 주문을 조작하고 서버 상태를 확인한다. 결과는 `artifacts/demo`와 `artifacts/qa`에 저장한다. 실제 카드 승인이나 배송 접수는 수행하지 않는다.
