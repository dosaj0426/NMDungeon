package com.nmdungeon.dungeon;

import com.nmdungeon.editor.ConnectorDefinition;
import com.nmdungeon.editor.DoorDefinition;
import com.nmdungeon.editor.DungeonDefinition;
import com.nmdungeon.editor.DungeonRepository;
import com.nmdungeon.editor.ModuleDefinition;
import com.nmdungeon.editor.PlayerPositionDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;

/** Builds a reproducible, connector-aligned plan from exported module schematics. */
public final class SchematicDungeonPlanFactory {
    private static final int FLOOR_Y = 64;
    private static final int PLAN_ATTEMPTS = 48;
    /** In room-corridor-room mode, crossroad templates are chosen four times as often as their base weight. */
    private static final int MULTI_DOOR_CORRIDOR_WEIGHT_MULTIPLIER = 4;

    private SchematicDungeonPlanFactory() {
    }

    public static DungeonPlan create(DungeonDefinition dungeon, DungeonRepository repository, World world,
                                     int slotOriginX, int slotOriginZ, long seed) {
        List<ModuleDefinition> available = dungeon.modules().values().stream()
                .filter(module -> repository.findSchematicFile(dungeon.id(), module.id()).isFile())
                .toList();
        requireType(available, ModuleType.ENTRANCE, "입구");
        requireType(available, ModuleType.BOSS, "보스방");
        requireType(available, ModuleType.EXIT, "출구");
        if (dungeon.enforceCombatRoomCorridors()) {
            requireType(available, ModuleType.CORRIDOR, "길");
        }

        IllegalArgumentException lastFailure = null;
        for (int attempt = 0; attempt < PLAN_ATTEMPTS; attempt++) {
            try {
                // A different deterministic stream per attempt avoids a dead-end random module choice.
                return createAttempt(dungeon, world, slotOriginX, slotOriginZ,
                        seed + 0x9E3779B97F4A7C15L * attempt, available);
            } catch (IllegalArgumentException exception) {
                lastFailure = exception;
            }
        }
        throw new IllegalArgumentException("연결 가능한 던전 경로를 찾지 못했습니다 ("
                + PLAN_ATTEMPTS + "개 조합 시도): " + lastFailure.getMessage());
    }

    private static DungeonPlan createAttempt(DungeonDefinition dungeon, World world, int slotOriginX, int slotOriginZ,
                                             long seed, List<ModuleDefinition> available) {
        Random random = new Random(seed);
        List<PlacedModule> placed = new ArrayList<>();
        Map<String, Integer> usage = new HashMap<>();
        ModuleDefinition entrance = pick(available, ModuleType.ENTRANCE, random, usage);
        ConnectorDefinition firstConnector = pickConnector(entrance.connectors(), random, entrance.id());
        PlacedModule root = placeRoot(entrance, slotOriginX, FLOOR_Y, slotOriginZ, 0, pickRotation(entrance, random));
        placed.add(root);
        count(usage, entrance);

        PlacedModule current = root;
        ConnectorDefinition outgoing = firstConnector;
        for (ModuleType type : mainPathTypes(dungeon, random)) {
            Connection next = connect(current, outgoing, available, List.of(type), placed, random, true, false, usage,
                    dungeon.enforceCombatRoomCorridors());
            placed.add(next.module());
            current = next.module();
            outgoing = next.nextConnector();
        }

        Connection boss = connect(current, outgoing, available, List.of(ModuleType.BOSS), placed, random, true, false, usage,
                dungeon.enforceCombatRoomCorridors());
        placed.add(boss.module());
        Connection exit = connect(boss.module(), boss.nextConnector(), available, List.of(ModuleType.EXIT), placed, random, false, false, usage,
                dungeon.enforceCombatRoomCorridors());
        placed.add(exit.module());

        int branches = 0;
        // In the monster-room → corridor rule, spend branch capacity on the remaining
        // corridor exits first. This lets a four-way cross use both non-main exits for
        // combat rooms instead of having unrelated modules consume the global limit first.
        if (dungeon.enforceCombatRoomCorridors()) {
            branches = addCorridorRoomBranches(dungeon, available, placed, random, usage);
        }

        // Add optional branches for the regular layout. Every unused connector is considered,
        // including two connectors on the same wall. Newly placed non-leaf rooms may also provide later branch points.
        for (int cursor = 0; cursor < placed.size() && branches < dungeon.maxBranches(); cursor++) {
            PlacedModule candidate = placed.get(cursor);
            if (candidate.definition().type() == ModuleType.BOSS || candidate.definition().type() == ModuleType.EXIT) continue;
            if (dungeon.enforceCombatRoomCorridors() && candidate.definition().type() == ModuleType.CORRIDOR) continue;
            List<ConnectorDefinition> branchConnectors = new ArrayList<>(candidate.unusedConnectors().stream()
                    .filter(ConnectorDefinition::allowBranch).toList());
            Collections.shuffle(branchConnectors, random);
            for (ConnectorDefinition branch : branchConnectors) {
                if (branches >= dungeon.maxBranches()) break;
                try {
                    Connection side = connect(candidate, branch, available, List.of(ModuleType.ROOM), placed, random, false, true, usage,
                            dungeon.enforceCombatRoomCorridors());
                    placed.add(side.module());
                    branches++;
                } catch (IllegalArgumentException ignored) {
                    // A branch is optional; the main progression must never fail because one cannot be placed.
                }
            }
        }

        List<String> missing = available.stream().filter(module -> usage.getOrDefault(module.id(), 0) < module.minInstances())
                .map(module -> module.id() + " " + usage.getOrDefault(module.id(), 0) + "/" + module.minInstances()).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("모듈 최소 생성 횟수를 충족하지 못했습니다: " + String.join(", ", missing));
        }

        List<ModulePlan> modules = placed.stream().map(PlacedModule::toPlan).toList();
        Bounds mapBounds = boundsOf(modules);
        PlayerEntryPoint configuredStart = root.playerPoint(root.definition().startPoint());
        Location start = configuredStart == null ? new Location(world,
                root.bounds().minX() + 1.5, root.bounds().minY() + 1, root.bounds().minZ() + 1.5)
                : configuredStart.toLocation(world);
        return new DungeonPlan(modules, mapBounds, start, copyItems(dungeon.clearRewards()), dungeon.clearRewardChances(),
                dungeon.requireAllModulesClearedForBoss());
    }

    private static int addCorridorRoomBranches(DungeonDefinition dungeon, List<ModuleDefinition> available,
                                               List<PlacedModule> placed, Random random, Map<String, Integer> usage) {
        int branches = 0;
        // Only the main-path corridor list exists at this point; added targets are ROOM modules.
        for (int index = 0; index < placed.size() && branches < dungeon.maxBranches(); index++) {
            PlacedModule corridor = placed.get(index);
            if (corridor.definition().type() != ModuleType.CORRIDOR) continue;
            List<ConnectorDefinition> unused = new ArrayList<>(corridor.unusedConnectors().stream()
                    .filter(ConnectorDefinition::allowBranch).toList());
            Collections.shuffle(unused, random);
            for (ConnectorDefinition connector : unused) {
                if (branches >= dungeon.maxBranches()) break;
                try {
                    Connection room = connect(corridor, connector, available, List.of(ModuleType.ROOM), placed, random,
                            false, true, usage, true);
                    placed.add(room.module());
                    branches++;
                } catch (IllegalArgumentException ignored) {
                    // The connector remains sealed when its compatible room would overlap another module
                    // or no compatible room schematic can be placed at that side.
                }
            }
        }
        return branches;
    }

    private static Connection connect(PlacedModule from, ConnectorDefinition outgoing, List<ModuleDefinition> available,
                                      List<ModuleType> types, List<PlacedModule> placed, Random random,
                                      boolean requiresContinuation, boolean preferLeaf, Map<String, Integer> usage,
                                      boolean enforceCombatRoomCorridors) {
        if (outgoing == null) {
            throw new IllegalArgumentException(from.definition().id() + " 모듈에 다음 진행용 연결점이 없습니다.");
        }
        List<Connection> options = new ArrayList<>();
        List<Connection> leafOptions = new ArrayList<>();
        for (ModuleDefinition definition : available) {
            if (!types.contains(definition.type())) continue;
            if (!allowsConnection(from.definition().type(), definition.type(), enforceCombatRoomCorridors)) continue;
            if (!canUse(definition, usage)) continue;
            for (int rotation : rotations(definition)) {
                for (ConnectorDefinition incoming : definition.connectors()) {
                    if (!compatible(outgoing, from.rotation(), incoming, rotation)) continue;
                    PlacedModule target = placeAdjacent(definition, incoming, rotation, from, outgoing, placed.size());
                    if (!overlapsAny(target.bounds(), placed)) {
                        target.use(incoming);
                        ConnectorDefinition next = pickUnusedConnector(target, random);
                        if (!requiresContinuation || next != null) {
                            Connection option = new Connection(target, next);
                            options.add(option);
                            if (target.definition().connectors().size() == 1) {
                                leafOptions.add(option);
                            }
                        }
                    }
                }
            }
        }
        List<Connection> selectable = preferLeaf && !leafOptions.isEmpty() ? leafOptions : options;
        if (selectable.isEmpty()) {
            throw new IllegalArgumentException("연결점 " + from.definition().id() + "/" + outgoing.id()
                    + "에 연결할 " + types + " schematic 모듈이 없습니다. 허용 회전, 방향·폭·높이, export 여부를 확인하세요.");
        }
        Connection selected = weighted(selectable, random, usage, enforceCombatRoomCorridors);
        // Reserve the source connector only after a target was actually selected.
        // Optional branch attempts can fail; those unconnected apertures must remain sealable.
        from.use(outgoing);
        count(usage, selected.module().definition());
        return selected;
    }

    private static boolean allowsConnection(ModuleType source, ModuleType target, boolean enforceCombatRoomCorridors) {
        if (!enforceCombatRoomCorridors) return true;
        // ROOM is the normal monster encounter module. Boss/exit keep their existing terminal-path
        // behavior so enabling this option does not require a separate corridor after every boss.
        if (source == ModuleType.ROOM) return target == ModuleType.CORRIDOR;
        if (target == ModuleType.ROOM) return source == ModuleType.CORRIDOR;
        return !(source == ModuleType.CORRIDOR && target == ModuleType.CORRIDOR);
    }

    private static List<ModuleType> mainPathTypes(DungeonDefinition dungeon, Random random) {
        List<ModuleType> types = new ArrayList<>();
        if (dungeon.enforceCombatRoomCorridors()) {
            // The corridor count is deliberately derived here, rather than constrained by the
            // configured corridor range. This preserves E -> C -> (R -> C)* -> Boss even when
            // the selected number of rooms needs more (or fewer) corridors than that range.
            int rooms = randomBetween(dungeon.minRooms(), dungeon.maxRooms(), random);
            for (int index = 0; index <= rooms; index++) {
                types.add(ModuleType.CORRIDOR);
                if (index < rooms) types.add(ModuleType.ROOM);
            }
            return types;
        }
        for (int index = 0; index < randomBetween(dungeon.minCorridors(), dungeon.maxCorridors(), random); index++) {
            types.add(ModuleType.CORRIDOR);
        }
        for (int index = 0; index < randomBetween(dungeon.minRooms(), dungeon.maxRooms(), random); index++) {
            types.add(ModuleType.ROOM);
        }
        if (types.isEmpty()) {
            throw new IllegalArgumentException("생성 규칙에 주 경로 길 또는 방이 하나 이상 필요합니다.");
        }
        Collections.shuffle(types, random);
        return types;
    }

    private static int randomBetween(int minimum, int maximum, Random random) {
        if (minimum < 0 || maximum < minimum) {
            throw new IllegalArgumentException("생성 규칙의 최소/최대 값이 올바르지 않습니다.");
        }
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private static PlacedModule placeRoot(ModuleDefinition definition, int x, int y, int z, int index, int rotation) {
        return new PlacedModule(index, definition, x, y, z, rotation);
    }

    private static PlacedModule placeAdjacent(ModuleDefinition definition, ConnectorDefinition incoming, int rotation,
                                              PlacedModule from, ConnectorDefinition outgoing, int index) {
        BlockPosition source = from.global(outgoing.x(), outgoing.y(), outgoing.z());
        int[] direction = vector(rotateFacing(outgoing.facing(), from.rotation()));
        BlockPosition rotatedIncoming = rotate(new BlockPosition(incoming.x(), incoming.y(), incoming.z()), rotation);
        return new PlacedModule(index, definition, source.x() + direction[0] - rotatedIncoming.x(),
                source.y() - rotatedIncoming.y(), source.z() + direction[1] - rotatedIncoming.z(), rotation);
    }

    private static ConnectorDefinition pickUnusedConnector(PlacedModule module, Random random) {
        List<ConnectorDefinition> unused = module.unusedConnectors();
        return unused.isEmpty() ? null : unused.get(random.nextInt(unused.size()));
    }

    private static ConnectorDefinition pickConnector(List<ConnectorDefinition> connectors, Random random, String moduleId) {
        if (connectors.isEmpty()) {
            throw new IllegalArgumentException(moduleId + " 모듈에 연결점이 없습니다.");
        }
        return connectors.get(random.nextInt(connectors.size()));
    }

    private static boolean compatible(ConnectorDefinition source, int sourceRotation, ConnectorDefinition target, int targetRotation) {
        return source.width() == target.width() && source.height() == target.height()
                && opposite(rotateFacing(source.facing(), sourceRotation)).equals(rotateFacing(target.facing(), targetRotation));
    }

    private static List<Integer> rotations(ModuleDefinition definition) {
        List<Integer> rotations = definition.allowedRotations().stream()
                .filter(rotation -> rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270)
                .sorted()
                .toList();
        if (rotations.isEmpty()) {
            throw new IllegalArgumentException(definition.id() + " 모듈의 허용 회전값은 0, 90, 180, 270 중 하나여야 합니다.");
        }
        return rotations;
    }

    private static int pickRotation(ModuleDefinition definition, Random random) {
        List<Integer> rotations = rotations(definition);
        return rotations.get(random.nextInt(rotations.size()));
    }

    /** Matches WorldEdit AffineTransform.rotateY: x' = z, z' = -x for +90 degrees. */
    private static BlockPosition rotate(BlockPosition point, int rotation) {
        return switch (rotation) {
            case 0 -> point;
            case 90 -> new BlockPosition(point.z(), point.y(), -point.x());
            case 180 -> new BlockPosition(-point.x(), point.y(), -point.z());
            case 270 -> new BlockPosition(-point.z(), point.y(), point.x());
            default -> throw new IllegalArgumentException("지원하지 않는 회전값: " + rotation);
        };
    }

    private static String rotateFacing(String facing, int rotation) {
        int turns = rotation / 90;
        String result = facing;
        for (int turn = 0; turn < turns; turn++) {
            result = switch (result) {
                case "NORTH" -> "WEST";
                case "WEST" -> "SOUTH";
                case "SOUTH" -> "EAST";
                case "EAST" -> "NORTH";
                default -> throw new IllegalArgumentException("잘못된 연결점 방향: " + result);
            };
        }
        return result;
    }

    private static String opposite(String facing) {
        return switch (facing) {
            case "NORTH" -> "SOUTH";
            case "SOUTH" -> "NORTH";
            case "EAST" -> "WEST";
            case "WEST" -> "EAST";
            default -> "";
        };
    }

    private static int[] vector(String facing) {
        return switch (facing) {
            case "NORTH" -> new int[] {0, -1};
            case "SOUTH" -> new int[] {0, 1};
            case "EAST" -> new int[] {1, 0};
            case "WEST" -> new int[] {-1, 0};
            default -> throw new IllegalArgumentException("잘못된 연결점 방향: " + facing);
        };
    }

    private static boolean doorFitsConnector(DoorDefinition door, ConnectorDefinition connector) {
        int minX = Math.min(door.pos1().x(), door.pos2().x());
        int maxX = Math.max(door.pos1().x(), door.pos2().x());
        int minY = Math.min(door.pos1().y(), door.pos2().y());
        int maxY = Math.max(door.pos1().y(), door.pos2().y());
        int minZ = Math.min(door.pos1().z(), door.pos2().z());
        int maxZ = Math.max(door.pos1().z(), door.pos2().z());
        int openingMinY = connector.y() - connector.height() / 2;
        int openingMaxY = openingMinY + connector.height() - 1;
        int openingMinHorizontal = -(connector.width() / 2);
        int openingMaxHorizontal = openingMinHorizontal + connector.width() - 1;
        return switch (connector.facing()) {
            case "NORTH", "SOUTH" -> minZ == connector.z() && maxZ == connector.z()
                    && minX >= connector.x() + openingMinHorizontal && maxX <= connector.x() + openingMaxHorizontal
                    && minY >= openingMinY && maxY <= openingMaxY;
            case "WEST", "EAST" -> minX == connector.x() && maxX == connector.x()
                    && minZ >= connector.z() + openingMinHorizontal && maxZ <= connector.z() + openingMaxHorizontal
                    && minY >= openingMinY && maxY <= openingMaxY;
            default -> false;
        };
    }

    private static boolean overlapsAny(Bounds candidate, List<PlacedModule> placed) {
        return placed.stream().map(PlacedModule::bounds).anyMatch(existing -> overlaps(candidate, existing));
    }

    private static boolean overlaps(Bounds a, Bounds b) {
        return a.minX() <= b.maxX() && a.maxX() >= b.minX()
                && a.minY() <= b.maxY() && a.maxY() >= b.minY()
                && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
    }

    private static Connection weighted(List<Connection> options, Random random, Map<String, Integer> usage,
                                       boolean preferMultiDoorCorridors) {
        List<Connection> required = options.stream().filter(option -> usage.getOrDefault(option.module().definition().id(), 0)
                < option.module().definition().minInstances()).toList();
        List<Connection> candidates = required.isEmpty() ? options : required;
        int total = candidates.stream().mapToInt(option -> weightedValue(option, preferMultiDoorCorridors)).sum();
        int roll = random.nextInt(total);
        for (Connection option : candidates) {
            roll -= weightedValue(option, preferMultiDoorCorridors);
            if (roll < 0) return option;
        }
        return candidates.getLast();
    }

    private static int weightedValue(Connection option, boolean preferMultiDoorCorridors) {
        ModuleDefinition definition = option.module().definition();
        int base = Math.max(1, definition.weight());
        if (!preferMultiDoorCorridors || definition.type() != ModuleType.CORRIDOR || branchDoorCount(definition) < 3) {
            return base;
        }
        return Math.min(Integer.MAX_VALUE / 2, base * MULTI_DOOR_CORRIDOR_WEIGHT_MULTIPLIER);
    }

    /** Counts authored door directions first, then supports older modules that only define connectors. */
    private static long branchDoorCount(ModuleDefinition definition) {
        long authoredDoorDirections = definition.doors().stream().map(DoorDefinition::connectorId).distinct().count();
        return authoredDoorDirections > 0 ? authoredDoorDirections
                : definition.connectors().stream().filter(ConnectorDefinition::allowBranch).count();
    }

    private static ModuleDefinition pick(List<ModuleDefinition> modules, ModuleType type, Random random, Map<String, Integer> usage) {
        List<ModuleDefinition> candidates = modules.stream().filter(module -> module.type() == type && canUse(module, usage)).toList();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(type + " 모듈이 없습니다.");
        }
        int total = candidates.stream().mapToInt(module -> Math.max(1, module.weight())).sum();
        int roll = random.nextInt(total);
        for (ModuleDefinition candidate : candidates) {
            roll -= Math.max(1, candidate.weight());
            if (roll < 0) return candidate;
        }
        return candidates.getLast();
    }

    private static boolean canUse(ModuleDefinition module, Map<String, Integer> usage) {
        return module.maxInstances() == 0 || usage.getOrDefault(module.id(), 0) < module.maxInstances();
    }

    private static void count(Map<String, Integer> usage, ModuleDefinition module) {
        usage.merge(module.id(), 1, Integer::sum);
    }

    private static void requireType(List<ModuleDefinition> modules, ModuleType type, String display) {
        if (modules.stream().noneMatch(module -> module.type() == type)) {
            throw new IllegalArgumentException(display + " 모듈의 .schem 파일이 없습니다. export 후 /nmdungeon edit save 하세요.");
        }
    }

    private static Bounds boundsOf(List<ModulePlan> modules) {
        return new Bounds(
                modules.stream().mapToInt(module -> module.bounds().minX()).min().orElse(0),
                modules.stream().mapToInt(module -> module.bounds().minY()).min().orElse(FLOOR_Y),
                modules.stream().mapToInt(module -> module.bounds().minZ()).min().orElse(0),
                modules.stream().mapToInt(module -> module.bounds().maxX()).max().orElse(0),
                modules.stream().mapToInt(module -> module.bounds().maxY()).max().orElse(FLOOR_Y),
                modules.stream().mapToInt(module -> module.bounds().maxZ()).max().orElse(0));
    }

    private record Connection(PlacedModule module, ConnectorDefinition nextConnector) {
    }

    private static final class PlacedModule {
        private final int index;
        private final ModuleDefinition definition;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final int rotation;
        private final Set<String> usedConnectorIds = new HashSet<>();
        private final List<ConnectorDefinition> outgoing = new ArrayList<>();
        private ConnectorDefinition incoming;

        private PlacedModule(int index, ModuleDefinition definition, int originX, int originY, int originZ, int rotation) {
            this.index = index;
            this.definition = definition;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.rotation = rotation;
        }

        private void use(ConnectorDefinition connector) {
            usedConnectorIds.add(connector.id());
            if (incoming == null) incoming = connector;
            else outgoing.add(connector);
        }

        private List<ConnectorDefinition> unusedConnectors() {
            return definition.connectors().stream().filter(connector -> !usedConnectorIds.contains(connector.id())).toList();
        }

        private Bounds bounds() {
            BlockPosition cornerA = rotate(new BlockPosition(0, 0, 0), rotation);
            BlockPosition cornerB = rotate(new BlockPosition(definition.sizeX() - 1, 0, 0), rotation);
            BlockPosition cornerC = rotate(new BlockPosition(0, 0, definition.sizeZ() - 1), rotation);
            BlockPosition cornerD = rotate(new BlockPosition(definition.sizeX() - 1, 0, definition.sizeZ() - 1), rotation);
            int minX = Math.min(Math.min(cornerA.x(), cornerB.x()), Math.min(cornerC.x(), cornerD.x()));
            int maxX = Math.max(Math.max(cornerA.x(), cornerB.x()), Math.max(cornerC.x(), cornerD.x()));
            int minZ = Math.min(Math.min(cornerA.z(), cornerB.z()), Math.min(cornerC.z(), cornerD.z()));
            int maxZ = Math.max(Math.max(cornerA.z(), cornerB.z()), Math.max(cornerC.z(), cornerD.z()));
            return new Bounds(originX + minX, originY, originZ + minZ,
                    originX + maxX, originY + definition.sizeY() - 1, originZ + maxZ);
        }

        private ModulePlan toPlan() {
            // A connector can have several door selections. They are one passage, so opening any
            // part of that passage must open every selection that belongs to the connector.
            List<List<BlockPosition>> entranceGroups = incoming == null ? List.of()
                    : List.of(connectorDoorBlocks(incoming));
            List<List<BlockPosition>> exitGroups = outgoing.stream().map(this::connectorDoorBlocks).toList();
            List<List<BlockPosition>> allDoorGroups = new ArrayList<>(entranceGroups);
            allDoorGroups.addAll(exitGroups);
            List<BlockPosition> entrance = entranceGroups.stream().flatMap(List::stream).toList();
            List<BlockPosition> exits = exitGroups.stream().flatMap(List::stream).toList();
            List<BlockPosition> sealed = unusedConnectors().stream().flatMap(connector -> connectorApertureBlocks(connector).stream()).toList();
            return new ModulePlan(index, definition.type(), bounds(), null, List.of(), entrance, exits, sealed, definition.id(), rotation,
                    new BlockPosition(originX, originY, originZ), List.copyOf(definition.waves()), definition.boss(),
                    List.copyOf(definition.spawnPoints()), List.copyOf(allDoorGroups),
                    playerPoint(incoming == null ? null : incoming.playerEntryPoint()), playerPoint(definition.checkpointPoint()),
                    copyItems(definition.clearRewards()), definition.clearRewardChances(), definition.clearCommands());
        }

        private List<BlockPosition> connectorDoorBlocks(ConnectorDefinition connector) {
            List<BlockPosition> blocks = definition.doors().stream()
                    .filter(candidate -> candidate.connectorId().equalsIgnoreCase(connector.id())
                            && doorFitsConnector(candidate, connector))
                    .flatMap(candidate -> doorBlocks(candidate).stream())
                    .distinct()
                    .toList();
            return blocks.isEmpty() ? connectorApertureBlocks(connector) : blocks;
        }

        private List<BlockPosition> connectorApertureBlocks(ConnectorDefinition connector) {
            List<BlockPosition> blocks = new ArrayList<>();
            int minY = connector.y() - (connector.height() / 2);
            for (int vertical = 0; vertical < connector.height(); vertical++) {
                for (int horizontal = -(connector.width() / 2); horizontal <= connector.width() / 2; horizontal++) {
                    if (connector.width() % 2 == 0 && horizontal == connector.width() / 2) continue;
                    int x = connector.x();
                    int z = connector.z();
                    if (connector.facing().equals("NORTH") || connector.facing().equals("SOUTH")) x += horizontal;
                    else z += horizontal;
                    blocks.add(global(x, minY + vertical, z));
                }
            }
            return blocks;
        }

        private List<BlockPosition> doorBlocks(DoorDefinition door) {
            List<BlockPosition> blocks = new ArrayList<>();
            for (int x = Math.min(door.pos1().x(), door.pos2().x()); x <= Math.max(door.pos1().x(), door.pos2().x()); x++) {
                for (int y = Math.min(door.pos1().y(), door.pos2().y()); y <= Math.max(door.pos1().y(), door.pos2().y()); y++) {
                    for (int z = Math.min(door.pos1().z(), door.pos2().z()); z <= Math.max(door.pos1().z(), door.pos2().z()); z++) {
                        blocks.add(global(x, y, z));
                    }
                }
            }
            return blocks;
        }

        private ModuleDefinition definition() { return definition; }
        private int rotation() { return rotation; }
        private PlayerEntryPoint playerPoint(PlayerPositionDefinition point) {
            if (point == null) return null;
            BlockPosition global = global(point.x(), point.y(), point.z());
            return new PlayerEntryPoint(global.x() + 0.5D, global.y(), global.z() + 0.5D,
                    normalizeYaw(point.yaw() + rotation), point.pitch());
        }

        private float normalizeYaw(float yaw) {
            float normalized = yaw % 360.0F;
            return normalized > 180.0F ? normalized - 360.0F : normalized < -180.0F ? normalized + 360.0F : normalized;
        }
        private BlockPosition global(int x, int y, int z) {
            BlockPosition point = rotate(new BlockPosition(x, y, z), rotation);
            return new BlockPosition(originX + point.x(), originY + point.y(), originZ + point.z());
        }
        private int originX() { return originX; }
        private int originY() { return originY; }
        private int originZ() { return originZ; }
    }

    private static List<org.bukkit.inventory.ItemStack> copyItems(List<org.bukkit.inventory.ItemStack> items) {
        return items.stream().map(org.bukkit.inventory.ItemStack::clone).toList();
    }
}
