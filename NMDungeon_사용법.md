# NMDungeon 사용법

NMDungeon은 FAWE schematic 모듈을 조합해 파티 전용 인스턴스 던전을 생성하는 Paper 플러그인입니다.

## 1. 지원 환경과 의존성

- 지원 서버: Paper `26.1.2 ~ 26.2`
- 명령어: `/nmdungeon`, `/nmd`, `/NM던전`
- 권장 Java: Java 25

| 플러그인 | 용도 | 필요 여부 |
| --- | --- | --- |
| FastAsyncWorldEdit (FAWE) | 모듈 선택, `.schem` export, 맵 붙여넣기 | 실제 던전 제작 시 필수 |
| Parties | 파티 생성·파티원 조회 | 던전 시작 시 필수 |
| MythicMobs | 웨이브 및 보스 스폰 | 전투 모듈 사용 시 필수 |
| PlaceholderAPI | 던전 상태 Placeholder | 선택 |
| Lucid | 보상 우편 전달 | 선택 |

`NMDungeon.jar`를 서버 `plugins/` 폴더에 넣은 후 서버를 재시작합니다.

## 2. 빠른 시작

1. Parties에서 파티를 만들고 모든 파티원을 온라인으로 둡니다.
2. `/nmdungeon create test`를 실행합니다.
3. `/nmdungeon edit test`로 편집 월드에 들어갑니다.
4. 핫바 9번의 **에디터 열기** 책을 우클릭해 GUI를 엽니다.
5. 입구, 길, 전투방, 보스방, 출구 모듈을 만들고 각각 FAWE로 export합니다.
6. 커넥터, 문, 시작 지점, 전투 스폰을 설정합니다.
7. **전체 검증** 후 저장합니다.
8. 파티장이 `/nmdungeon start test`를 실행합니다.

파티가 없거나 파티장이 아니면 던전을 시작할 수 없습니다. 맵 생성이 끝나면 모든 파티원이 같은 인스턴스에 입장합니다.

## 3. GUI 에디터

`/nmdungeon edit <던전ID>`를 실행하면 전용 평지 편집 월드가 열립니다. 에디터 책은 허공 우클릭으로도 GUI를 열 수 있습니다.

### 모듈 유형

| 유형 | 설명 |
| --- | --- |
| `ENTRANCE` | 파티 시작 위치가 있는 입구 |
| `CORRIDOR` | 모듈을 연결하는 길 |
| `ROOM` | 웨이브를 처리하는 일반 전투방 |
| `BOSS` | 보스를 처리하는 보스방 |
| `EXIT` | 던전 완료 지점 |

모듈을 지은 뒤 FAWE 완드로 모듈 전체의 두 모서리를 선택하고, 모듈 상세의 **현재 FAWE 선택 영역 export**를 누릅니다. X/Z 크기는 16의 배수일 필요가 없습니다.

### 커넥터와 문

- 커넥터는 모듈 외벽을 바라본 상태에서 등록합니다.
- 서로 연결되는 커넥터는 방향이 마주 보고 폭·높이가 같아야 합니다.
- `allow-branch`가 켜진 커넥터만 분기 후보가 됩니다.
- 하나의 커넥터에 문 영역을 여러 개 등록할 수 있으며, 모두 하나의 통로로 열립니다.
- 연결되지 않은 커넥터는 생성 시 닫힌 상태로 막힙니다.
- 전투방을 클리어하면 지나온 문은 즉시 열리고, 다음 문에는 버튼이 설치됩니다.

### 플레이어 위치

모듈 export 후 모듈 내부의 원하는 위치에 서서 GUI에서 설정합니다.

- **시작 지점**: 입구 모듈에서 던전 시작 시 파티가 도착할 위치
- **커넥터 진입 지점**: 해당 문을 통과했을 때의 위치 및 시선 방향
- **체크포인트**: 사망 후 부활 위치. 없으면 들어온 문 안쪽 위치를 사용

Yaw/Pitch도 저장되며, 모듈이 회전해 생성되면 시선 방향도 함께 회전합니다.

### 전투와 스폰

`모듈 상세 → 전투·스폰 관리`에서 설정합니다.

- **스폰 지점**: 현재 위치를 모듈 로컬 좌표로 저장
- **웨이브**: 스폰 지점, MythicMob ID, 수량, 레벨 설정
- **보스**: 스폰 지점, MythicMob ID, 레벨 설정

추적 중인 몬스터는 플레이어 처치 여부와 관계없이 **사망하면** 다음 웨이브 또는 클리어 판정으로 진행합니다.

### 보상과 클리어 명령어

- **던전 완료 보상**: 메인 에디터에서 출구 도달 시 지급할 아이템과 확률을 설정합니다.
- **모듈 클리어 보상**: 모듈 상세에서 해당 모듈을 처음 클리어할 때 지급할 아이템과 확률을 설정합니다.
- 보상 GUI에서는 아래 플레이어 인벤토리의 아이템을 좌클릭해 등록합니다. 등록 아이템은 좌클릭으로 확률 입력, 우클릭으로 삭제합니다.
- **모듈 클리어 명령어**: 모듈 상세에서 설정합니다. 예: `give {player} diamond 1`
  - 모듈이 클리어될 때마다 파티의 온라인 플레이어 각각에 대해 콘솔 명령으로 실행됩니다.
  - `{player}`는 대상 플레이어 이름으로 치환됩니다.

## 4. 생성 규칙

`생성·도전 제한` GUI에서 설정합니다.

- 길/방 최소·최대 수
- 최대 분기 수
- 공유 목숨 제한 (`0`은 무제한)
- 던전 시간 제한 (`0`은 무제한)
- 전투방 길 연결 규칙
- 보스방 입장 조건

### 전투방 길 연결 규칙

켜면 기본 주 경로는 다음과 같습니다.

```text
입구 → 길 → 전투방 → 길 → 전투방 → 길 → 보스방 → 출구
```

- 전투방(`ROOM`)은 길(`CORRIDOR`)에만 연결됩니다.
- 길끼리 직접 연결되지 않습니다.
- 길 수 설정은 이 규칙에서는 강제되지 않으며, 방 수에 맞춰 자동으로 증감합니다.
- 4방향 길의 남은 `allow-branch: true` 커넥터는 전투방 분기로 우선 사용됩니다.
- 문 또는 분기 가능 커넥터가 3개 이상인 길은 기본 가중치의 4배로 선택되어 분기형 길이 더 자주 생성됩니다.

중요: 길에 큰 전투방을 네 방향으로 직접 붙이려면 실제 schematic 크기도 충분해야 합니다. 예를 들어 `15×15` 길에 `27×27` 전투방을 네 방향으로 붙이면 전투방끼리 겹치므로 안전하게 모두 생성할 수 없습니다. 이 경우 4방향 길 모듈을 전투방 크기 이상으로 넓게 제작해 다시 export해야 합니다.

### 보스방 입장 조건

**보스방 조건: 모든 모듈 클리어**를 켜면 입구·길·전투방을 모두 클리어하기 전에는 보스방 문 버튼이 작동하지 않습니다. 버튼은 사라지지 않으며 안내만 표시됩니다.

## 5. 던전 플레이

- 생성 중 파티원 보스바에 진행률이 표시됩니다.
- 전투방 진입 시 들어온 문이 닫히며, 방 조건을 해결하기 전에는 나갈 수 없습니다.
- 다음 문의 중앙 버튼을 누르면 문 흔들림·하강 애니메이션 후 문이 열립니다.
- 한 번에 하나의 문 애니메이션만 실행됩니다.
- 문이 열린 뒤 약 1초 후, 아직 다음 방에 들어가지 않은 파티원은 다음 방 안으로 이동합니다.
- 던전 안에서는 블록 설치와 파괴가 차단됩니다.
- 파티 일반 멤버가 퇴장하면 파티와 던전에서 제외됩니다. 파티장이 퇴장하면 세션 전체가 끝납니다.

### 탐험 지도

던전 입장 시 파티원에게 탐험 지도가 지급됩니다.

- 진입한 모듈과 연결 구조만 공개됩니다.
- 보스방은 미탐험 상태여도 별 아이콘으로 위치를 표시합니다.
- 세션 종료 또는 진행 중 퇴장 시 지도는 자동 회수됩니다.

## 6. 명령어

`[]`는 선택값, `<>`는 필수값입니다.

| 명령어 | 설명 |
| --- | --- |
| `/nmdungeon start [dungeonId]` | 파티장이 던전을 시작합니다. ID 생략 시 MVP 테스트 던전입니다. |
| `/nmdungeon start <dungeonId> <partyPlayer>` | 콘솔 또는 관리자가 지정 플레이어의 파티로 던전을 시작합니다. |
| `/nmdungeon end [player]` | 자신의 던전 또는 지정 플레이어의 던전을 강제 종료합니다. |
| `/nmdungeon status` | 현재 모듈, 목숨, 제한 시간을 확인합니다. |
| `/nmdungeon clear` | 현재 전투방을 테스트 목적으로 클리어합니다. |
| `/nmdungeon create <dungeonId>` | 빈 던전 정의를 만듭니다. |
| `/nmdungeon delete <dungeonId>` | 던전 설정, schematic, 편집 월드를 영구 삭제합니다. |
| `/nmdungeon edit <dungeonId>` | 편집 세션과 전용 편집 월드를 엽니다. |
| `/nmdungeon edit <save\|discard\|exit\|book>` | 편집 저장, 변경 폐기, 종료, 에디터 책 재지급 |
| `/nmdungeon module create <id> <type>` | 모듈을 만듭니다. |
| `/nmdungeon module export <moduleId>` | 현재 FAWE 선택 영역을 schematic으로 저장합니다. |
| `/nmdungeon module list` | 현재 편집 던전의 모듈 목록을 표시합니다. |
| `/nmdungeon connector add <moduleId> <connectorId> [width height]` | 바라보는 외벽에 커넥터를 등록합니다. |
| `/nmdungeon connector resize <moduleId> <connectorId> <width> <height>` | 커넥터 크기를 변경합니다. |
| `/nmdungeon connector remove <moduleId> <connectorId>` | 커넥터를 삭제합니다. |
| `/nmdungeon connector list <moduleId>` | 모듈의 커넥터 목록을 표시합니다. |
| `/nmdungeon door set <moduleId> <doorId> <connectorId> <condition>` | 현재 FAWE 선택 영역을 문으로 등록합니다. |
| `/nmdungeon door remove <moduleId> <doorId>` | 문을 삭제합니다. |
| `/nmdungeon door list <moduleId>` | 모듈의 문 목록을 표시합니다. |
| `/nmdungeon spawn add <moduleId> <spawnPointId>` | 현재 위치를 몬스터 스폰 지점으로 등록합니다. |
| `/nmdungeon wave add <moduleId> <waveId> <spawnPointId> <mythicMobId> <amount> [level]` | 웨이브 몬스터를 등록합니다. |
| `/nmdungeon boss set <moduleId> <spawnPointId> <mythicMobId> [level]` | 보스를 등록합니다. |
| `/nmdungeon rules list` | 수치형 생성 규칙을 확인합니다. |
| `/nmdungeon rules set <rule> <number>` | 수치형 생성 규칙을 변경합니다. |
| `/nmdungeon validate [dungeonId]` | 던전 정의를 검증합니다. |
| `/nmdungeon loadtest <dungeonId> [runs]` | 실제 schematic 생성·정리 부하 테스트를 실행합니다. |
| `/nmdungeon reload` | `config.yml`, `message.yml`, `sound.yml`을 다시 읽습니다. |

한국어 별칭도 지원합니다. 예: `/NM던전 시작 test`, `/NM던전 편집 test`, `/NM던전 삭제 test`.

`delete`는 진행 중이거나 편집 중인 던전, 또는 편집 월드에 플레이어가 있는 던전을 삭제하지 않습니다.

## 7. 권한

| 권한 | 설명 | 기본값 |
| --- | --- | --- |
| `nmdungeon.command` | 일반 NMDungeon 명령어 | OP |
| `nmdungeon.reload` | 설정 리로드 | OP |
| `nmdungeon.loadtest` | 부하 테스트 | OP |
| `nmdungeon.delete` | 던전 영구 삭제 | OP |

## 8. 설정 파일

서버의 `plugins/NMDungeon/` 폴더를 사용합니다.

| 파일 | 용도 |
| --- | --- |
| `config.yml` | 생성 동시 수, 모듈 최대 크기, 생성 보스바, 지도, 기본 제한 |
| `message.yml` | 플레이어 메시지 문구 |
| `sound.yml` | 문 애니메이션 사운드 |
| `dungeons/<dungeonId>/dungeon.yml` | 던전·모듈·커넥터·문·전투·보상 설정 |
| `dungeons/<dungeonId>/schematics/*.schem` | export된 모듈 schematic |

### 문 사운드 설정 예시

```yml
door-opening:
  start:
    enabled: true
    sound: BLOCK_IRON_DOOR_OPEN
    volume: 1.0
    pitch: 0.8
  complete:
    enabled: true
    sound: BLOCK_CHAIN_BREAK
    volume: 0.9
    pitch: 1.0
```

`sound`에는 Bukkit `Sound` enum 이름을 넣습니다. 설정 변경 후 `/nmdungeon reload`를 실행합니다.

### 메시지 표시 제어

`message.yml`의 문구를 수정할 수 있으며, `config.yml`의 `messages.enabled` 아래에서 특정 메시지 표시를 끌 수 있습니다.

```yml
messages:
  enabled:
    all: true
    dungeon.door-opening: false
```

## 9. PlaceholderAPI

PlaceholderAPI가 설치된 경우 다음 Placeholder를 사용할 수 있습니다.

| Placeholder | 값 |
| --- | --- |
| `%nmdungeon_active%` | 던전 진행 중이면 `true` |
| `%nmdungeon_id%` | 던전 ID |
| `%nmdungeon_state%` | 인스턴스 상태 |
| `%nmdungeon_lives%`, `%nmdungeon_lives_remaining%` | 남은 공유 목숨 |
| `%nmdungeon_lives_max%` | 최대 공유 목숨 |
| `%nmdungeon_time%`, `%nmdungeon_time_remaining%` | 남은 시간 `MM:SS` |
| `%nmdungeon_time_remaining_seconds%` | 남은 시간(초) |
| `%nmdungeon_time_limit_seconds%` | 전체 제한 시간(초) |

## 10. 실행 전 확인 목록

1. 입구·보스방·출구가 각각 하나 이상 있는지 확인합니다.
2. 실제 생성에 사용할 모든 모듈이 export되어 `.schem` 파일이 있는지 확인합니다.
3. 연결될 커넥터의 방향·폭·높이가 서로 맞는지 확인합니다.
4. 각 사용 커넥터에 문 영역이 등록되어 있는지 확인합니다.
5. 시작 지점, 진입 지점, 체크포인트가 안전한 모듈 내부 위치인지 확인합니다.
6. 웨이브/보스의 MythicMob ID와 스폰 지점이 유효한지 확인합니다.
7. 전투방 길 연결 규칙을 켠 경우, 길과 전투방의 실제 크기가 분기 배치에 충분한지 확인합니다.
8. `/nmdungeon validate <dungeonId>`를 통과한 뒤 파티로 실행합니다.
