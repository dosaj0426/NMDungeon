package com.nmdungeon.editor;

import com.nmdungeon.dungeon.ModuleType;
import com.nmdungeon.dungeon.BlockPosition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

public final class DungeonValidator {
    private final int maxSizeX;
    private final int maxSizeY;
    private final int maxSizeZ;

    public DungeonValidator(FileConfiguration config) {
        this.maxSizeX = positive(config.getInt("max-module-size.x", 64), 64);
        this.maxSizeY = positive(config.getInt("max-module-size.y", 32), 32);
        this.maxSizeZ = positive(config.getInt("max-module-size.z", 64), 64);
    }

    public List<String> validate(DungeonDefinition dungeon) {
        List<String> messages = new ArrayList<>();
        if (dungeon.modules().isEmpty()) {
            messages.add("등록된 모듈이 없습니다.");
            return messages;
        }
        requireType(messages, dungeon, ModuleType.ENTRANCE, "입구 모듈");
        requireType(messages, dungeon, ModuleType.BOSS, "보스방 모듈");
        requireType(messages, dungeon, ModuleType.EXIT, "출구 모듈");
        validateGenerationRules(messages, dungeon);
        for (ModuleDefinition module : dungeon.modules().values()) {
            if (module.maxInstances() > 0 && module.minInstances() > module.maxInstances()) {
                messages.add(module.id() + ": 최소 생성 횟수가 최대 생성 횟수보다 큽니다.");
            }
            if ((module.type() == ModuleType.ENTRANCE || module.type() == ModuleType.BOSS || module.type() == ModuleType.EXIT)
                    && module.minInstances() > 1) {
                messages.add(module.id() + ": " + module.type() + " 모듈은 한 던전에 최대 1개만 생성할 수 있습니다.");
            }
            if (module.sizeX() <= 0 || module.sizeY() <= 0 || module.sizeZ() <= 0) {
                messages.add(module.id() + ": 모듈 크기는 0보다 커야 합니다.");
            }
            if (module.sizeX() > maxSizeX || module.sizeY() > maxSizeY || module.sizeZ() > maxSizeZ) {
                messages.add(module.id() + ": 모듈 최대 크기(" + maxSizeX + "×" + maxSizeY + "×" + maxSizeZ + ")를 초과했습니다.");
            }
            if (module.allowedRotations().isEmpty()) {
                messages.add(module.id() + ": 허용 회전이 하나 이상 필요합니다.");
            } else if (module.allowedRotations().stream().anyMatch(rotation -> rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
                messages.add(module.id() + ": 허용 회전값은 0, 90, 180, 270만 사용할 수 있습니다.");
            }
            validateConnectors(messages, module);
            validatePlayerPositions(messages, module);
            validateDoors(messages, module);
            validateEncounters(messages, module);
        }
        return messages;
    }

    private void validateGenerationRules(List<String> messages, DungeonDefinition dungeon) {
        if (dungeon.minCorridors() < 0 || dungeon.maxCorridors() < dungeon.minCorridors()) {
            messages.add("생성 규칙: 길 수는 0 이상이며 최소값이 최대값보다 작거나 같아야 합니다.");
        }
        if (dungeon.minRooms() < 0 || dungeon.maxRooms() < dungeon.minRooms()) {
            messages.add("생성 규칙: 방 수는 0 이상이며 최소값이 최대값보다 작거나 같아야 합니다.");
        }
        if (!dungeon.enforceCombatRoomCorridors() && dungeon.minCorridors() + dungeon.minRooms() < 1) {
            messages.add("생성 규칙: 주 경로에는 길 또는 방이 최소 한 개 필요합니다.");
        }
        if (dungeon.maxBranches() < 0) {
            messages.add("생성 규칙: 최대 분기 수는 0 이상이어야 합니다.");
        }
        if (dungeon.enforceCombatRoomCorridors()
                && dungeon.modules().values().stream().noneMatch(module -> module.type() == ModuleType.CORRIDOR)) {
            messages.add("생성 규칙: 전투방 길 연결 규칙에는 길(CORRIDOR) 모듈이 하나 이상 필요합니다.");
        }
    }

    private void requireType(List<String> messages, DungeonDefinition dungeon, ModuleType type, String displayName) {
        if (dungeon.modules().values().stream().noneMatch(module -> module.type() == type)) {
            messages.add(displayName + "이(가) 없습니다.");
        }
    }

    private void validateConnectors(List<String> messages, ModuleDefinition module) {
        Set<String> ids = new HashSet<>();
        for (ConnectorDefinition connector : module.connectors()) {
            if (!ids.add(connector.id().toLowerCase())) {
                messages.add(module.id() + ": 중복된 연결점 ID " + connector.id());
            }
            if (connector.width() < 1 || connector.height() < 1 || connector.width() > 15 || connector.height() > 15) {
                messages.add(module.id() + "/" + connector.id() + ": 연결점 크기는 1~15여야 합니다.");
            }
            BlockPosition position = new BlockPosition(connector.x(), connector.y(), connector.z());
            if (!inside(module, position) || !onFace(module, position, connector.facing())) {
                messages.add(module.id() + "/" + connector.id() + ": 연결점이 지정된 외벽 면에 있지 않습니다.");
            }
            if (connector.playerEntryPoint() != null && !inside(module, toBlockPosition(connector.playerEntryPoint()))) {
                messages.add(module.id() + "/" + connector.id() + ": 플레이어 진입 지점이 모듈 내부에 있지 않습니다.");
            }
        }
    }

    private void validatePlayerPositions(List<String> messages, ModuleDefinition module) {
        if (module.startPoint() != null) {
            if (module.type() != ModuleType.ENTRANCE) messages.add(module.id() + ": 시작 지점은 입구 모듈에만 설정할 수 있습니다.");
            if (!inside(module, toBlockPosition(module.startPoint()))) messages.add(module.id() + ": 시작 지점이 모듈 내부에 있지 않습니다.");
        }
        if (module.checkpointPoint() != null && !inside(module, toBlockPosition(module.checkpointPoint()))) {
            messages.add(module.id() + ": 체크포인트가 모듈 내부에 있지 않습니다.");
        }
    }

    private BlockPosition toBlockPosition(PlayerPositionDefinition point) {
        return new BlockPosition(point.x(), point.y(), point.z());
    }

    private void validateDoors(List<String> messages, ModuleDefinition module) {
        Set<String> ids = new HashSet<>();
        for (DoorDefinition door : module.doors()) {
            if (!ids.add(door.id().toLowerCase())) {
                messages.add(module.id() + ": 중복된 문 ID " + door.id());
            }
            ConnectorDefinition connector = module.findConnector(door.connectorId());
            if (connector == null) {
                messages.add(module.id() + "/" + door.id() + ": 연결된 연결점이 없습니다.");
                continue;
            }
            if (!inside(module, door.pos1()) || !inside(module, door.pos2()) || !withinConnectorOpening(door, connector)) {
                messages.add(module.id() + "/" + door.id() + ": 문 영역이 연결점의 폭·높이 범위와 일치하지 않습니다.");
            }
        }
        for (int first = 0; first < module.doors().size(); first++) {
            for (int second = first + 1; second < module.doors().size(); second++) {
                DoorDefinition a = module.doors().get(first);
                DoorDefinition b = module.doors().get(second);
                if (!a.connectorId().equalsIgnoreCase(b.connectorId()) && overlaps(a, b)) {
                    messages.add(module.id() + ": 문 " + a.id() + "와 " + b.id() + "가 서로 겹칩니다.");
                }
            }
        }
    }

    private void validateEncounters(List<String> messages, ModuleDefinition module) {
        Set<String> spawnPointIds = new HashSet<>();
        for (SpawnPointDefinition point : module.spawnPoints()) {
            if (!spawnPointIds.add(point.id().toLowerCase())) messages.add(module.id() + ": 중복된 스폰 지점 ID " + point.id());
            if (!inside(module, point.position())) messages.add(module.id() + "/" + point.id() + ": 스폰 지점이 모듈 내부에 있지 않습니다.");
        }
        for (WaveDefinition wave : module.waves()) {
            if (wave.spawns().isEmpty()) messages.add(module.id() + "/" + wave.id() + ": 웨이브에 몬스터가 없습니다.");
            for (WaveSpawnDefinition spawn : wave.spawns()) {
                if (module.findSpawnPoint(spawn.spawnPointId()) == null) messages.add(module.id() + "/" + wave.id() + ": 없는 스폰 지점 " + spawn.spawnPointId());
                if (spawn.mythicMobId().isBlank() || spawn.amount() < 1 || spawn.level() <= 0) messages.add(module.id() + "/" + wave.id() + ": MythicMob/수량/레벨 설정이 올바르지 않습니다.");
            }
        }
        if (module.boss() != null && (module.findSpawnPoint(module.boss().spawnPointId()) == null || module.boss().mythicMobId().isBlank()
                || module.boss().level() <= 0)) {
            messages.add(module.id() + ": 보스 스폰 설정이 올바르지 않습니다.");
        }
    }

    private boolean inside(ModuleDefinition module, BlockPosition point) {
        return point.x() >= 0 && point.x() < module.sizeX() && point.y() >= 0 && point.y() < module.sizeY()
                && point.z() >= 0 && point.z() < module.sizeZ();
    }

    private boolean onFace(ModuleDefinition module, BlockPosition point, String facing) {
        return switch (facing) {
            case "NORTH" -> point.z() == 0;
            case "SOUTH" -> point.z() == module.sizeZ() - 1;
            case "WEST" -> point.x() == 0;
            case "EAST" -> point.x() == module.sizeX() - 1;
            default -> false;
        };
    }

    private boolean withinConnectorOpening(DoorDefinition door, ConnectorDefinition connector) {
        BlockPosition pos1 = door.pos1();
        BlockPosition pos2 = door.pos2();
        int minY = connector.y() - connector.height() / 2;
        int maxY = minY + connector.height() - 1;
        int minHorizontal = -(connector.width() / 2);
        int maxHorizontal = minHorizontal + connector.width() - 1;
        return switch (connector.facing()) {
            case "NORTH", "SOUTH" -> pos1.z() == connector.z() && pos2.z() == connector.z()
                    && Math.min(pos1.x(), pos2.x()) >= connector.x() + minHorizontal
                    && Math.max(pos1.x(), pos2.x()) <= connector.x() + maxHorizontal
                    && Math.min(pos1.y(), pos2.y()) >= minY && Math.max(pos1.y(), pos2.y()) <= maxY;
            case "WEST", "EAST" -> pos1.x() == connector.x() && pos2.x() == connector.x()
                    && Math.min(pos1.z(), pos2.z()) >= connector.z() + minHorizontal
                    && Math.max(pos1.z(), pos2.z()) <= connector.z() + maxHorizontal
                    && Math.min(pos1.y(), pos2.y()) >= minY && Math.max(pos1.y(), pos2.y()) <= maxY;
            default -> false;
        };
    }

    private boolean overlaps(DoorDefinition first, DoorDefinition second) {
        return Math.max(Math.min(first.pos1().x(), first.pos2().x()), Math.min(second.pos1().x(), second.pos2().x()))
                        <= Math.min(Math.max(first.pos1().x(), first.pos2().x()), Math.max(second.pos1().x(), second.pos2().x()))
                && Math.max(Math.min(first.pos1().y(), first.pos2().y()), Math.min(second.pos1().y(), second.pos2().y()))
                        <= Math.min(Math.max(first.pos1().y(), first.pos2().y()), Math.max(second.pos1().y(), second.pos2().y()))
                && Math.max(Math.min(first.pos1().z(), first.pos2().z()), Math.min(second.pos1().z(), second.pos2().z()))
                        <= Math.min(Math.max(first.pos1().z(), first.pos2().z()), Math.max(second.pos1().z(), second.pos2().z()));
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
