# Pleos SDK 후속 수정 검증 요약

기준 시점: 2026-10-28 23:49 UTC. 이 문서는 독립 검수를 통과한 소스 반영의 근거이며, 새 배포·실차 검증·전체 SDK 성공을 뜻하지 않는다.

- 앱 JUnit 244개, XML 29개: 실패·오류·건너뜀 0.
- 관련 소스 44개 SHA 일치. 소스 manifest SHA-256: `3192df0054ec59ea0a5ce367d237a716ead5691b7500b4d3a551eb75c02bcfe3`.
- PleosDebug APK 76,184,917바이트, APK와 설치본 SHA-256: `26f7611cf918756b08db550f1dce898a60fb82435956ec8aa898100f8df104f5`. debug 서명이며 release가 아니다.
- 검증 자료 ZIP: 138개 항목, CRC·내용 해시 일치. SHA-256: `d34cf4cb025c37fd871ce6eeb5b78e73e81bafc47cafc71a58a3767e57ddc123`.

## 수정과 회귀

영역별 문·창문 읽기와 listener 수명주기, 취소 토큰, stale 응답·초기값/이벤트 경합, Main 밖 SDK 작업, 실패 객체 보존과 정리 재시도 제한을 검증했다. 모르는 raw 위치값을 닫힘으로 추정하지 않는다. 신뢰할 수 있는 PARKED 공급 계약을 확인하지 못했으므로 제어 차단을 유지한다.

이번 최종 수정은 자동 `retire` 알림이 소진된 예산을 다시 여는 경로를 막는다. 자동 lifecycle/callback/finally와 일반 내비 진입 검사는 예산을 재개하지 않는다. 확인된 사용자 작업 경계만 별도의 제한된 재시도를 허용한다. 내비 정리의 유일한 스케줄러는 process owner이며, 이미 허용된 작업을 기다리는 500ms 대기는 새 재시도를 만들지 않는다.

처음의 예상 10회/실제 7회 실패는 기존 15초 native backoff를 고려하지 않은 테스트 기대에서 발생했다. 최종 회귀는 조정된 coordinator 시각별 누적 호출 `[6,6,6,6,7]`, backoff 만료 뒤 자동 진입 20회에도 추가 호출 0, 세 번째 명시적 요청의 성공 및 새 factory 진입을 각각 확인한다. backoff를 느슨하게 하거나 단순히 마지막 기대값만 낮추지 않았다.

## 실제 AVD와 모의 검증의 구분

공식 기존 AVD에서 동일 bridge·adapter·assistant의 STOP(epoch 2)→START(epoch 3), ViewModel close 없음, 새 SOC 구독 100을 관측했다. 문·창문 unregister/register 각 10건, CarUx unregister/disconnect/register 각 1건, Fused unregister/register 각 1건, SDK read 36건이 모두 Main 밖이었다. 이 일반 재개에서는 Vehicle/Fused 전체 release가 필요하지 않다.

별도 Activity 재생성에서는 이전 Vehicle/Fused release, 새 owner 등록 및 새 adapter의 accepted SOC 100을 확인했다. 일반 재개와 전체 재생성 결과를 혼동하지 않는다. 희귀 `active_reuse` 경합과 실제 영구 정리 실패는 실기 재현이 아닌 모의 회귀 증거다.

SmartGlass 60001·목표 온도 오류·초기 SOC capability timeout은 보존했다. 첫 캡처의 실패 terminal 42건과 SOC 진단 1건을 오류 증거 85행과 혼동하지 않는다. 마지막 동일 ViewModel 캡처에도 SmartGlass 실패 1요청(콜백+terminal 2행)이 남았다. 실제 마이크/스피커와 안내 시작은 실행하지 않았으며, 실차 제어·새 영상·배포도 수행하지 않았다. 이 단계의 API는 격리된 demo/sqlite였으므로 이전 live 고캠핑 검증이나 영상의 새 증거로 사용하지 않는다.

## 자료 보존과 원격 범위

[시점별 상세 기록](SDK_RETRY_BUDGET_20261028_2305.json)의 `mainModifiedOrPushed:false`, pending/failed 등은 해당 기록 생성 시점의 상태다. 이후 main 반영 여부는 Git 커밋 이력으로 확인한다. `review-runtime`의 원시 로그·XML·APK·ZIP은 로컬에 보존하며 원격에 포함하지 않는다. 원격에는 검증된 소스·회귀 테스트와 필요한 요약 문서만 반영한다. 이전 실패 영수증과 영상은 삭제하지 않는다.
