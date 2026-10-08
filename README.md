# NMDungeon MVP

Paper 기반 생성형 인스턴스 던전 MVP입니다. 이 단계는 schematic 자산 없이도 다음 흐름을 검증합니다.

```text
/nmdungeon start
  → Void 월드에 모듈형 테스트 던전 생성
  → 플레이어 입장
  → 방 진입 시 출구 문 잠금
  → /nmdungeon clear 로 조건 충족을 시뮬레이션
  → 출구 문 열림
  → 보스방 이후 출구 진입 또는 /nmdungeon end
  → 생성 영역 정리 및 슬롯 반환
```

`/nmdungeon start` 테스트 맵은 보스까지 이어지는 동쪽 주 경로와, 첫 전투방에서 열리는 북쪽 선택 분기를 함께 생성합니다.

## 명령어

- `/nmdungeon start` — 새 고정 MVP 인스턴스를 생성하고 입장합니다.
- `/nmdungeon start <dungeonId>` — 저장·export된 모듈 schematic을 커넥터 기준으로 조합해 인스턴스를 생성합니다.
- `/nmdungeon clear` — 현재 전투방 또는 보스방의 클리어 조건을 테스트합니다.
- `/nmdungeon status` — 현재 인스턴스와 모듈 상태를 봅니다.
- `/nmdungeon end` — 현재 던전을 종료하고 생성 영역을 정리합니다.
- `/nmdungeon create <dungeonId>` — 편집할 빈 던전 정의를 만듭니다.
- `/nmdungeon edit <dungeonId>` — 편집 전용 Void 월드와 GUI 대시보드를 엽니다.
- `/nmdungeon edit save|discard|exit` — 편집 세션의 저장·폐기·종료를 처리합니다.
- `/nmdungeon module create <moduleId> <type>` — 편집 세션에 기본 모듈을 등록합니다.
- `/nmdungeon module export <moduleId>` — FAWE 완드 선택 영역을 `<moduleId>.schem`으로 비동기 저장합니다.
- `/nmdungeon connector add <moduleId> <connectorId> [width height]` — 바라보는 모듈 외벽에 연결점을 등록합니다.
- `/nmdungeon connector resize <moduleId> <connectorId> <width> <height>` — 연결점 위치·방향을 유지한 채 크기를 변경합니다.
- `/nmdungeon module list` — 현재 편집 던전의 모듈 목록을 봅니다.
- `/nmdungeon connector list <moduleId>` — 모듈의 연결점 목록을 봅니다.
- `/nmdungeon door list <moduleId>` — 모듈의 문 목록을 봅니다.
- `/nmdungeon rules list` — 현재 던전의 길·방·분기 생성 범위를 봅니다.
- `/nmdungeon rules set <rule> <number>` — 생성 범위를 변경합니다. (`min-corridors`, `max-corridors`, `min-rooms`, `max-rooms`, `max-branches`)
- `/nmdungeon spawn add <moduleId> <spawnPointId>` — 현재 위치를 모듈 내부 몬스터 스폰 지점으로 저장합니다.
- `/nmdungeon wave add <moduleId> <waveId> <spawnPointId> <mythicMobId> <amount> [level]` — 웨이브에 MythicMob 스폰을 추가합니다. 같은 waveId에 여러 번 추가할 수 있습니다.
- `/nmdungeon boss set <moduleId> <spawnPointId> <mythicMobId> [level]` — 일반 웨이브 후 등장할 보스를 설정합니다.
- `/nmdungeon door set <moduleId> <doorId> <connectorId> CLEAR_ROOM` — FAWE 선택 영역을 문으로 등록합니다.
- `/nmdungeon validate [dungeonId]` — 필수 모듈·크기·격자 규칙을 검사합니다.

`/nmdungeon edit`는 던전 ID별 평지 작업 월드(`nm_edit_<dungeonId>`)를 사용합니다. 이 월드의 건축물은 편집 종료 후에도 저장됩니다. FAWE 선택 영역을 내보내면 `plugins/NMDungeon/dungeons/<dungeonId>/schematics/<moduleId>.schem`에 저장됩니다.

## 빌드

Paper 26.1.2는 Java 25가 필요합니다.

```powershell
cd plugin
mvn package
```

결과물은 `target/NMDungeon.jar`입니다.

## 모듈 schematic 던전 실행

`/nmdungeon start <dungeonId>`는 FAWE로 `.schem`을 비동기 paste합니다. 플래너는 같은 폭·높이이면서 서로 반대 방향을 향하는 커넥터만 연결하며, 겹치는 배치는 제외합니다. 가중치(`weight`)가 높은 모듈은 선택될 확률이 높습니다. 직접 연결할 수 없으면 모듈별 `rotations`의 `0/90/180/270`도 함께 탐색하고, 선택된 각도 그대로 schematic·연결점·문 좌표를 회전해 배치합니다.

현재 첫 구현의 생성 규칙은 `ENTRANCE → 일반 모듈 2개(ROOM/CORRIDOR) → BOSS → EXIT`입니다. 여유 커넥터 중 `allow-branch: true`인 것이 있으면 선택 전투방을 최대 다섯 개까지 추가 배치합니다.

실행 전에 모든 사용 모듈을 export하고 설정을 저장해야 합니다. 입구는 연결점 1개 이상, 일반·보스 모듈은 진행을 위해 연결점 2개 이상, 출구는 연결점 1개 이상이 필요합니다. 등록한 문 영역이 있으면 그 영역을 사용하고, 없으면 커넥터 폭·높이 범위를 임시 문으로 사용합니다. 모든 모듈과 허용 회전을 검사해도 연결 가능한 경로가 없으면 paste 전에 생성이 취소됩니다.
"# NMDungeon" 
