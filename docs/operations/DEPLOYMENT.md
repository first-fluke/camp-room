# API 배포 구성

CampRoom API는 홈서버의 Docker에서 실행하고 Cloudflare Tunnel을 통해 HTTPS로 제공합니다.

| 구성 | 역할 |
| --- | --- |
| 공개 API | `https://camp-room-api.firstfluke.com/api/v1` |
| 상태 확인 | [API 상태](https://camp-room-api.firstfluke.com/health) |
| FastAPI | 캠핑장·사진·날씨 조회와 여행 데이터 처리 |
| Turso | 여행 데이터와 공급자 캐시 저장 |
| 고캠핑·기상청 | 캠핑장 정보·사진·시간별 예보 제공 |

차량 관측은 앱의 Pleos SDK에서 읽고, 서버 연결 정보는 환경변수로 관리합니다. 개발 실행 방법과 전체 구성도는 [README](../../README.md)에 정리했습니다.
