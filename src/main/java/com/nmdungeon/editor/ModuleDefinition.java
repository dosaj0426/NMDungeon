package com.nmdungeon.editor;

import com.nmdungeon.dungeon.ModuleType;
import com.nmdungeon.dungeon.BlockPosition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.inventory.ItemStack;

public final class ModuleDefinition {
    private final String id;
    private ModuleType type;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private int weight;
    /** 0 maximum means unlimited; minimum is fulfilled preferentially during planning. */
    private int minInstances;
    private int maxInstances;
    private BlockPosition templateOrigin;
    private final Set<Integer> allowedRotations;
    private final List<ConnectorDefinition> connectors;
    private final List<DoorDefinition> doors;
    private final List<MarkerDefinition> markers;
    private final List<SpawnPointDefinition> spawnPoints;
    private final List<WaveDefinition> waves;
    private final List<ItemStack> clearRewards;
    private final List<Double> clearRewardChances;
    /** Console commands run once for every online party member when this module is cleared. */
    private final List<String> clearCommands;
    private BossDefinition boss;
    private PlayerPositionDefinition startPoint;
    private PlayerPositionDefinition checkpointPoint;

    public ModuleDefinition(String id, ModuleType type, int sizeX, int sizeY, int sizeZ, int weight,
                            Set<Integer> allowedRotations, List<ConnectorDefinition> connectors,
                            List<DoorDefinition> doors, List<MarkerDefinition> markers) {
        this.id = id;
        this.type = type;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.weight = weight;
        if (type == ModuleType.ENTRANCE || type == ModuleType.BOSS || type == ModuleType.EXIT) {
            this.minInstances = 1;
            this.maxInstances = 1;
        }
        this.allowedRotations = new LinkedHashSet<>(allowedRotations);
        this.connectors = new ArrayList<>(connectors);
        this.doors = new ArrayList<>(doors);
        this.markers = new ArrayList<>(markers);
        this.spawnPoints = new ArrayList<>();
        this.waves = new ArrayList<>();
        this.clearRewards = new ArrayList<>();
        this.clearRewardChances = new ArrayList<>();
        this.clearCommands = new ArrayList<>();
    }

    public static ModuleDefinition createDefault(String id, ModuleType type) {
        int horizontalSize = type == ModuleType.BOSS ? 48 : type == ModuleType.CORRIDOR ? 16 : 32;
        return new ModuleDefinition(id, type, horizontalSize, 16, horizontalSize, 10,
                Set.of(0, 90, 180, 270), List.of(), List.of(), List.of());
    }

    public String id() { return id; }
    public ModuleType type() { return type; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public int weight() { return weight; }
    public int minInstances() { return minInstances; }
    public int maxInstances() { return maxInstances; }
    public Set<Integer> allowedRotations() { return allowedRotations; }
    public List<ConnectorDefinition> connectors() { return connectors; }
    public List<DoorDefinition> doors() { return doors; }
    public List<MarkerDefinition> markers() { return markers; }
    public List<SpawnPointDefinition> spawnPoints() { return spawnPoints; }
    public List<WaveDefinition> waves() { return waves; }
    public BossDefinition boss() { return boss; }
    public List<ItemStack> clearRewards() { return clearRewards.stream().map(ItemStack::clone).toList(); }
    public List<Double> clearRewardChances() { return List.copyOf(clearRewardChances); }
    public double clearRewardChance(int index) { return index < 0 || index >= clearRewardChances.size() ? 100.0D : clearRewardChances.get(index); }
    public List<String> clearCommands() { return List.copyOf(clearCommands); }
    public BlockPosition templateOrigin() { return templateOrigin; }
    public PlayerPositionDefinition startPoint() { return startPoint; }
    public PlayerPositionDefinition checkpointPoint() { return checkpointPoint; }

    public void setSize(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    public void setWeight(int weight) {
        this.weight = Math.max(1, weight);
    }

    public void setInstanceLimits(int minimum, int maximum) {
        this.minInstances = Math.max(0, minimum);
        this.maxInstances = Math.max(0, maximum);
    }

    public boolean toggleRotation(int rotation) {
        if (allowedRotations.contains(rotation)) {
            if (allowedRotations.size() == 1) return false;
            allowedRotations.remove(rotation);
            return true;
        }
        allowedRotations.add(rotation);
        return true;
    }

    public void setTemplateOrigin(BlockPosition templateOrigin) {
        this.templateOrigin = templateOrigin;
    }

    public boolean addConnector(ConnectorDefinition connector) {
        if (connectors.stream().anyMatch(existing -> existing.id().equalsIgnoreCase(connector.id()))) {
            return false;
        }
        connectors.add(connector);
        return true;
    }

    public boolean removeConnector(String connectorId) {
        return connectors.removeIf(connector -> connector.id().equalsIgnoreCase(connectorId));
    }

    public boolean resizeConnector(String connectorId, int width, int height) {
        for (int index = 0; index < connectors.size(); index++) {
            ConnectorDefinition connector = connectors.get(index);
            if (!connector.id().equalsIgnoreCase(connectorId)) {
                continue;
            }
            connectors.set(index, new ConnectorDefinition(connector.id(), connector.facing(), connector.x(), connector.y(), connector.z(),
                    width, height, connector.allowBranch(), connector.tags(), connector.playerEntryPoint()));
            return true;
        }
        return false;
    }

    public boolean toggleConnectorBranch(String connectorId) {
        for (int index = 0; index < connectors.size(); index++) {
            ConnectorDefinition connector = connectors.get(index);
            if (!connector.id().equalsIgnoreCase(connectorId)) continue;
            connectors.set(index, new ConnectorDefinition(connector.id(), connector.facing(), connector.x(), connector.y(), connector.z(),
                    connector.width(), connector.height(), !connector.allowBranch(), connector.tags(), connector.playerEntryPoint()));
            return true;
        }
        return false;
    }

    public ConnectorDefinition findConnector(String connectorId) {
        return connectors.stream().filter(connector -> connector.id().equalsIgnoreCase(connectorId)).findFirst().orElse(null);
    }

    public boolean setConnectorEntryPoint(String connectorId, PlayerPositionDefinition point) {
        for (int index = 0; index < connectors.size(); index++) {
            ConnectorDefinition connector = connectors.get(index);
            if (!connector.id().equalsIgnoreCase(connectorId)) continue;
            connectors.set(index, new ConnectorDefinition(connector.id(), connector.facing(), connector.x(), connector.y(), connector.z(),
                    connector.width(), connector.height(), connector.allowBranch(), connector.tags(), point));
            return true;
        }
        return false;
    }

    public boolean addDoor(DoorDefinition door) {
        if (doors.stream().anyMatch(existing -> existing.id().equalsIgnoreCase(door.id()))) {
            return false;
        }
        doors.add(door);
        return true;
    }

    public boolean removeDoor(String doorId) {
        return doors.removeIf(door -> door.id().equalsIgnoreCase(doorId));
    }

    public boolean addSpawnPoint(SpawnPointDefinition spawnPoint) {
        if (spawnPoints.stream().anyMatch(existing -> existing.id().equalsIgnoreCase(spawnPoint.id()))) return false;
        spawnPoints.add(spawnPoint);
        return true;
    }

    public SpawnPointDefinition findSpawnPoint(String spawnPointId) {
        return spawnPoints.stream().filter(point -> point.id().equalsIgnoreCase(spawnPointId)).findFirst().orElse(null);
    }

    public boolean removeSpawnPoint(String spawnPointId) {
        return spawnPoints.removeIf(point -> point.id().equalsIgnoreCase(spawnPointId));
    }

    public WaveDefinition findOrCreateWave(String waveId) {
        WaveDefinition existing = waves.stream().filter(wave -> wave.id().equalsIgnoreCase(waveId)).findFirst().orElse(null);
        if (existing != null) return existing;
        WaveDefinition created = new WaveDefinition(waveId, List.of());
        waves.add(created);
        return created;
    }

    public WaveDefinition findWave(String waveId) {
        return waves.stream().filter(wave -> wave.id().equalsIgnoreCase(waveId)).findFirst().orElse(null);
    }

    public boolean removeWave(String waveId) {
        return waves.removeIf(wave -> wave.id().equalsIgnoreCase(waveId));
    }

    public void setBoss(BossDefinition boss) { this.boss = boss; }
    public void addClearReward(ItemStack item) { if (item != null && !item.getType().isAir()) { clearRewards.add(item.clone()); clearRewardChances.add(100.0D); } }
    public boolean removeClearReward(int index) { if (index < 0 || index >= clearRewards.size()) return false; clearRewards.remove(index); clearRewardChances.remove(index); return true; }
    public void setClearRewards(List<ItemStack> items) { setClearRewards(items, List.of()); }
    public void setClearRewards(List<ItemStack> items, List<Double> chances) {
        clearRewards.clear(); clearRewardChances.clear();
        for (int index = 0; index < items.size(); index++) {
            ItemStack item = items.get(index);
            if (item == null || item.getType().isAir()) continue;
            clearRewards.add(item.clone());
            clearRewardChances.add(clampChance(index < chances.size() ? chances.get(index) : 100.0D));
        }
    }
    public void setClearRewardChance(int index, double chance) { if (index >= 0 && index < clearRewardChances.size()) clearRewardChances.set(index, clampChance(chance)); }
    public boolean addClearCommand(String command) {
        if (command == null) return false;
        String normalized = command.trim();
        if (normalized.startsWith("/")) normalized = normalized.substring(1).trim();
        if (normalized.isEmpty() || clearCommands.size() >= 45) return false;
        clearCommands.add(normalized);
        return true;
    }
    public boolean removeClearCommand(int index) {
        if (index < 0 || index >= clearCommands.size()) return false;
        clearCommands.remove(index);
        return true;
    }
    public void setClearCommands(List<String> commands) {
        clearCommands.clear();
        if (commands != null) commands.forEach(this::addClearCommand);
    }
    private static double clampChance(double chance) { return Math.max(0.0D, Math.min(100.0D, chance)); }
    public void setStartPoint(PlayerPositionDefinition startPoint) { this.startPoint = startPoint; }
    public void setCheckpointPoint(PlayerPositionDefinition checkpointPoint) { this.checkpointPoint = checkpointPoint; }
}
