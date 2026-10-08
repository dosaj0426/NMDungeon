package com.nmdungeon.editor;

import com.nmdungeon.NMDungeonPlugin;
import com.nmdungeon.dungeon.ModuleType;
import com.nmdungeon.dungeon.BlockPosition;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public final class DungeonRepository {
    private final File dungeonsDirectory;

    public DungeonRepository(NMDungeonPlugin plugin) {
        dungeonsDirectory = new File(plugin.getDataFolder(), "dungeons");
        if (!dungeonsDirectory.exists() && !dungeonsDirectory.mkdirs()) {
            throw new IllegalStateException("던전 저장 폴더를 만들 수 없습니다.");
        }
    }

    public boolean exists(String dungeonId) {
        return definitionFile(dungeonId).isFile();
    }

    /** Returns dungeon IDs that have a saved dungeon.yml definition. */
    public List<String> dungeonIds() {
        File[] directories = dungeonsDirectory.listFiles(File::isDirectory);
        if (directories == null) {
            return List.of();
        }
        return Arrays.stream(directories)
                .filter(directory -> new File(directory, "dungeon.yml").isFile())
                .map(File::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public DungeonDefinition create(String dungeonId) throws IOException {
        DungeonDefinition definition = new DungeonDefinition(dungeonId);
        save(definition);
        return definition;
    }

    /** Deletes only this dungeon's dedicated data folder (definition and exported schematics). */
    public boolean delete(String dungeonId) throws IOException {
        File folder = new File(dungeonsDirectory, dungeonId);
        if (!folder.isDirectory()) return false;
        if (!folder.getCanonicalFile().toPath().startsWith(dungeonsDirectory.getCanonicalFile().toPath())) {
            throw new IOException("Invalid dungeon deletion path.");
        }
        try (var paths = Files.walk(folder.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        return true;
    }

    public DungeonDefinition load(String dungeonId) throws IOException {
        File file = definitionFile(dungeonId);
        if (!file.isFile()) {
            throw new IOException("던전 설정 파일이 없습니다.");
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        DungeonDefinition definition = new DungeonDefinition(yaml.getString("id", dungeonId));
        definition.setGenerationRules(yaml.getInt("generation.min-corridors", 1), yaml.getInt("generation.max-corridors", 2),
                yaml.getInt("generation.min-rooms", 1), yaml.getInt("generation.max-rooms", 2),
                yaml.getInt("generation.max-branches", 5));
        definition.setEnforceCombatRoomCorridors(yaml.getBoolean("generation.enforce-combat-room-corridors", false));
        definition.setRequireAllModulesClearedForBoss(yaml.getBoolean("generation.boss-room-requires-all-modules-cleared", false));
        definition.setLimits(yaml.getInt("limits.max-lives", 0), yaml.getInt("limits.time-limit-seconds", 0));
        RewardList dungeonRewards = readDungeonRewards(yaml.getMapList("clear-rewards"));
        definition.setClearRewards(dungeonRewards.items(), dungeonRewards.chances());
        ConfigurationSection modules = yaml.getConfigurationSection("modules");
        if (modules == null) {
            return definition;
        }
        for (String moduleId : modules.getKeys(false)) {
            ConfigurationSection section = modules.getConfigurationSection(moduleId);
            if (section == null) {
                continue;
            }
            ModuleType type;
            try {
                type = ModuleType.valueOf(section.getString("type", "ROOM"));
            } catch (IllegalArgumentException ignored) {
                type = ModuleType.ROOM;
            }
            ModuleDefinition module = new ModuleDefinition(moduleId, type,
                    section.getInt("size.x", 32), section.getInt("size.y", 16), section.getInt("size.z", 32),
                    section.getInt("weight", 10), Set.copyOf(section.getIntegerList("rotations")),
                    List.of(), List.of(), List.of());
            int defaultExactOne = (type == ModuleType.ENTRANCE || type == ModuleType.BOSS || type == ModuleType.EXIT) ? 1 : 0;
            module.setInstanceLimits(section.getInt("instances.min", defaultExactOne), section.getInt("instances.max", defaultExactOne));
            if (section.contains("origin.x")) {
                module.setTemplateOrigin(new BlockPosition(section.getInt("origin.x"), section.getInt("origin.y"), section.getInt("origin.z")));
            }
            ConfigurationSection connectors = section.getConfigurationSection("connectors");
            if (connectors != null) {
                for (String connectorId : connectors.getKeys(false)) {
                    ConfigurationSection connector = connectors.getConfigurationSection(connectorId);
                    if (connector == null) continue;
                    module.addConnector(new ConnectorDefinition(connectorId, connector.getString("facing", "NORTH"),
                            connector.getInt("position.x"), connector.getInt("position.y"), connector.getInt("position.z"),
                            connector.getInt("width", 5), connector.getInt("height", 5), connector.getBoolean("allow-branch", true),
                            List.copyOf(connector.getStringList("tags")), readPlayerPosition(connector.getConfigurationSection("player-entry"))));
                }
            }
            module.setStartPoint(readPlayerPosition(section.getConfigurationSection("player-start")));
            module.setCheckpointPoint(readPlayerPosition(section.getConfigurationSection("checkpoint")));
            RewardList moduleRewards = readDungeonRewards(section.getMapList("clear-rewards"));
            module.setClearRewards(moduleRewards.items(), moduleRewards.chances());
            module.setClearCommands(section.getStringList("clear-commands"));
            ConfigurationSection doors = section.getConfigurationSection("doors");
            if (doors != null) {
                for (String doorId : doors.getKeys(false)) {
                    ConfigurationSection door = doors.getConfigurationSection(doorId);
                    if (door == null) continue;
                    module.addDoor(new DoorDefinition(doorId, door.getString("connector"), door.getString("condition", "CLEAR_ROOM"),
                            new BlockPosition(door.getInt("pos1.x"), door.getInt("pos1.y"), door.getInt("pos1.z")),
                            new BlockPosition(door.getInt("pos2.x"), door.getInt("pos2.y"), door.getInt("pos2.z"))));
                }
            }
            ConfigurationSection spawnPoints = section.getConfigurationSection("spawn-points");
            if (spawnPoints != null) {
                for (String pointId : spawnPoints.getKeys(false)) {
                    ConfigurationSection point = spawnPoints.getConfigurationSection(pointId);
                    if (point != null) module.addSpawnPoint(new SpawnPointDefinition(pointId,
                            new BlockPosition(point.getInt("x"), point.getInt("y"), point.getInt("z"))));
                }
            }
            ConfigurationSection waves = section.getConfigurationSection("waves");
            if (waves != null) {
                for (String waveId : waves.getKeys(false)) {
                    ConfigurationSection wave = waves.getConfigurationSection(waveId);
                    if (wave == null) continue;
                    WaveDefinition definitionWave = module.findOrCreateWave(waveId);
                    for (Map<?, ?> entry : wave.getMapList("spawns")) {
                        Object point = entry.get("spawn-point");
                        Object mob = entry.get("mythic-mob");
                        if (point == null || mob == null) continue;
                        int amount = entry.get("amount") instanceof Number number ? number.intValue() : 1;
                        double level = entry.get("level") instanceof Number number ? number.doubleValue() : 1.0;
                        definitionWave.addSpawn(new WaveSpawnDefinition(point.toString(), mob.toString(), amount, level));
                    }
                }
            }
            ConfigurationSection boss = section.getConfigurationSection("boss");
            if (boss != null && boss.contains("mythic-mob")) {
                module.setBoss(new BossDefinition(boss.getString("spawn-point"), boss.getString("mythic-mob"), boss.getDouble("level", 1.0)));
            }
            definition.addModule(module);
        }
        return definition;
    }

    public void save(DungeonDefinition definition) throws IOException {
        File file = definitionFile(definition.id());
        File folder = file.getParentFile();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("던전 폴더를 만들 수 없습니다.");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        yaml.set("id", definition.id());
        yaml.set("generation.min-corridors", definition.minCorridors());
        yaml.set("generation.max-corridors", definition.maxCorridors());
        yaml.set("generation.min-rooms", definition.minRooms());
        yaml.set("generation.max-rooms", definition.maxRooms());
        yaml.set("generation.max-branches", definition.maxBranches());
        yaml.set("generation.enforce-combat-room-corridors", definition.enforceCombatRoomCorridors());
        yaml.set("generation.boss-room-requires-all-modules-cleared", definition.requireAllModulesClearedForBoss());
        yaml.set("limits.max-lives", definition.maxLives());
        yaml.set("limits.time-limit-seconds", definition.timeLimitSeconds());
        yaml.set("clear-rewards", writeDungeonRewards(definition.clearRewards(), definition.clearRewardChances()));
        for (ModuleDefinition module : definition.modules().values()) {
            String path = "modules." + module.id();
            yaml.set(path + ".type", module.type().name());
            yaml.set(path + ".size.x", module.sizeX());
            yaml.set(path + ".size.y", module.sizeY());
            yaml.set(path + ".size.z", module.sizeZ());
            yaml.set(path + ".weight", module.weight());
            yaml.set(path + ".instances.min", module.minInstances());
            yaml.set(path + ".instances.max", module.maxInstances());
            yaml.set(path + ".rotations", new ArrayList<>(module.allowedRotations()));
            if (module.templateOrigin() != null) {
                yaml.set(path + ".origin.x", module.templateOrigin().x());
                yaml.set(path + ".origin.y", module.templateOrigin().y());
                yaml.set(path + ".origin.z", module.templateOrigin().z());
            }
            for (ConnectorDefinition connector : module.connectors()) {
                String connectorPath = path + ".connectors." + connector.id();
                yaml.set(connectorPath + ".facing", connector.facing());
                yaml.set(connectorPath + ".position.x", connector.x());
                yaml.set(connectorPath + ".position.y", connector.y());
                yaml.set(connectorPath + ".position.z", connector.z());
                yaml.set(connectorPath + ".width", connector.width());
                yaml.set(connectorPath + ".height", connector.height());
                yaml.set(connectorPath + ".allow-branch", connector.allowBranch());
                yaml.set(connectorPath + ".tags", connector.tags());
                writePlayerPosition(yaml, connectorPath + ".player-entry", connector.playerEntryPoint());
            }
            writePlayerPosition(yaml, path + ".player-start", module.startPoint());
            writePlayerPosition(yaml, path + ".checkpoint", module.checkpointPoint());
            yaml.set(path + ".clear-rewards", writeDungeonRewards(module.clearRewards(), module.clearRewardChances()));
            yaml.set(path + ".clear-commands", module.clearCommands());
            for (DoorDefinition door : module.doors()) {
                String doorPath = path + ".doors." + door.id();
                yaml.set(doorPath + ".connector", door.connectorId());
                yaml.set(doorPath + ".condition", door.conditionType());
                yaml.set(doorPath + ".pos1.x", door.pos1().x());
                yaml.set(doorPath + ".pos1.y", door.pos1().y());
                yaml.set(doorPath + ".pos1.z", door.pos1().z());
                yaml.set(doorPath + ".pos2.x", door.pos2().x());
                yaml.set(doorPath + ".pos2.y", door.pos2().y());
                yaml.set(doorPath + ".pos2.z", door.pos2().z());
            }
            for (SpawnPointDefinition point : module.spawnPoints()) {
                String pointPath = path + ".spawn-points." + point.id();
                yaml.set(pointPath + ".x", point.position().x());
                yaml.set(pointPath + ".y", point.position().y());
                yaml.set(pointPath + ".z", point.position().z());
            }
            for (WaveDefinition wave : module.waves()) {
                String wavePath = path + ".waves." + wave.id() + ".spawns";
                List<Map<String, Object>> spawns = new ArrayList<>();
                for (WaveSpawnDefinition spawn : wave.spawns()) {
                    java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("spawn-point", spawn.spawnPointId());
                    entry.put("mythic-mob", spawn.mythicMobId());
                    entry.put("amount", spawn.amount());
                    entry.put("level", spawn.level());
                    spawns.add(entry);
                }
                yaml.set(wavePath, spawns);
            }
            if (module.boss() != null) {
                yaml.set(path + ".boss.spawn-point", module.boss().spawnPointId());
                yaml.set(path + ".boss.mythic-mob", module.boss().mythicMobId());
                yaml.set(path + ".boss.level", module.boss().level());
            }
        }
        yaml.save(file);
    }

    public File schematicFile(String dungeonId, String moduleId) throws IOException {
        File directory = new File(new File(dungeonsDirectory, dungeonId), "schematics");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("schematic 폴더를 만들 수 없습니다.");
        }
        return new File(directory, moduleId + ".schem");
    }

    public File findSchematicFile(String dungeonId, String moduleId) {
        return new File(new File(new File(dungeonsDirectory, dungeonId), "schematics"), moduleId + ".schem");
    }

    private File definitionFile(String dungeonId) {
        return new File(new File(dungeonsDirectory, dungeonId), "dungeon.yml");
    }

    private PlayerPositionDefinition readPlayerPosition(ConfigurationSection section) {
        if (section == null) return null;
        return new PlayerPositionDefinition(section.getInt("x"), section.getInt("y"), section.getInt("z"),
                (float) section.getDouble("yaw", 0.0D), (float) section.getDouble("pitch", 0.0D));
    }

    private void writePlayerPosition(YamlConfiguration yaml, String path, PlayerPositionDefinition point) {
        if (point == null) return;
        yaml.set(path + ".x", point.x());
        yaml.set(path + ".y", point.y());
        yaml.set(path + ".z", point.z());
        yaml.set(path + ".yaw", point.yaw());
        yaml.set(path + ".pitch", point.pitch());
    }

    private List<ItemStack> readRewards(List<Map<?, ?>> entries) {
        List<ItemStack> rewards = new ArrayList<>();
        for (Map<?, ?> entry : entries) {
            try {
                Map<String, Object> serialized = new java.util.LinkedHashMap<>();
                entry.forEach((key, value) -> serialized.put(String.valueOf(key), value));
                ItemStack item = ItemStack.deserialize(serialized);
                if (!item.getType().isAir()) rewards.add(item);
            } catch (RuntimeException ignored) {
                // Ignore only the malformed reward entry; the rest of the dungeon remains usable.
            }
        }
        return rewards;
    }

    private List<Map<String, Object>> writeRewards(List<ItemStack> rewards) {
        return rewards.stream().filter(item -> item != null && !item.getType().isAir())
                .map(ItemStack::serialize).toList();
    }

    private RewardList readDungeonRewards(List<Map<?, ?>> entries) {
        List<ItemStack> items = new ArrayList<>();
        List<Double> chances = new ArrayList<>();
        for (Map<?, ?> entry : entries) {
            try {
                Object rawItem = entry.get("item");
                Map<?, ?> data = rawItem instanceof Map<?, ?> map ? map : entry; // supports the old plain-item list format.
                Map<String, Object> serialized = new java.util.LinkedHashMap<>();
                data.forEach((key, value) -> serialized.put(String.valueOf(key), value));
                ItemStack item = ItemStack.deserialize(serialized);
                if (item.getType().isAir()) continue;
                items.add(item);
                Object rawChance = entry.get("chance");
                double chance = rawChance instanceof Number number ? number.doubleValue() : 100.0D;
                chances.add(Math.max(0.0D, Math.min(100.0D, chance)));
            } catch (RuntimeException ignored) {
            }
        }
        return new RewardList(items, chances);
    }

    private List<Map<String, Object>> writeDungeonRewards(List<ItemStack> items, List<Double> chances) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            ItemStack item = items.get(index);
            if (item == null || item.getType().isAir()) continue;
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("item", item.serialize());
            entry.put("chance", index < chances.size() ? Math.max(0.0D, Math.min(100.0D, chances.get(index))) : 100.0D);
            result.add(entry);
        }
        return result;
    }

    private record RewardList(List<ItemStack> items, List<Double> chances) {
    }
}
