# OMA 설치 및 실행 증거

2026-10-25에 공식 first-fluke/oh-my-agent npm 패키지 `20.13.0`을 설치했다. 공식 CLI의 `oma install --semantic-memory none`을 실행하여 아래 선택으로 전체 설치를 완료했다. 직접 관리 SSOT 정의를 편집하지 않았다.

- 언어: 한국어
- CLI: Codex
- code intelligence: Serena
- web search: runtime native
- memory: none (워크플로 파일 증거 사용)
- model preset: auto (사용자가 지정한 Codex Sol Ultra 리드의 native dispatch model 상속)
- project preset: All
- backend: Python
- browser: 최초 none; 사용자 Aside 지시 후 root가 공식 `oma update mcp --vendor codex`로 Aside 선택

설치 종료코드는 0이다. `oma doctor --json`은 33/33 installed, 33 complete, Codex0.163.0 installed/authenticated, Serena2.1.0 installed, Codex MCP configured를 확인했다. 전체 doctor는 ok=false/13 issues를 보고한다. 미사용 vendor CLI 부재, starter의 다른 vendor hook-order 경고와 선택하지 않은 전역 Git 권장 설정 등의 진단을 포함하므로 진단 전체 통과로 표현하지 않는다. 원본 JSON은 oma-doctor.json이다. 실제 hook 신뢰가 필요한 런타임 안내도 설치 결과에 포함되어 있다.

동일 vendor native OMA 역할 정의와 skill을 읽은 후 backend/frontend 역할을 병렬 dispatch했다. 각 작업은 `oma agent begin`, frozen plan/required_checks, 고유 run/claim, 실제 check와 result/finish 계약을 사용한다. 현재 호스트에 Serena tool이 노출되어 있지 않아 developer 지시에 따른 native search 및 scoped read로 구현 범위를 확인했다. provider 사용 가능으로 꾸미지 않는다.

## 재현

```bash
npm install --global oh-my-agent@20.13.0
oma install --semantic-memory none
# Korean / Codex / Auto / All / Python 선택
oma doctor --json
```

기본 installer의 명시적 -y 옵션은 인증된 gh가 있으면 upstream star 부수효과가 있으므로 이번에는 interactive 설치에서 그러한 외부 작업을 선택하지 않았다. 실제 프로젝트는 feature/camp-room 브랜치이며 upstream은 starter remote, push/publish는 수행하지 않았다.
