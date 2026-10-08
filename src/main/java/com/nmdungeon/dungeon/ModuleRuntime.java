package com.nmdungeon.dungeon;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public final class ModuleRuntime {
    private final ModulePlan plan;
    private final List<BlockPosition> entranceDoorBlocks;
    private final List<BlockPosition> exitDoorBlocks;
    private final List<List<BlockPosition>> doorGroups;
    private final Map<BlockPosition, String> closedDoorBlockData = new ConcurrentHashMap<>();
    private final Set<java.util.UUID> aliveEncounterMobs = ConcurrentHashMap.newKeySet();
    private final Set<Integer> openedDoorGroups = ConcurrentHashMap.newKeySet();
    private final Map<Integer, BlockPosition> doorButtons = new ConcurrentHashMap<>();
    private int nextWaveIndex;
    private boolean bossSpawned;
    private ModuleState state = ModuleState.INACTIVE;

    public ModuleRuntime(ModulePlan plan) {
        this.plan = plan;
        this.entranceDoorBlocks = plan.entranceDoorwayBlocks();
        this.exitDoorBlocks = plan.exitDoorBlocks();
        this.doorGroups = plan.doorGroups().isEmpty() ? List.of(lockDoorBlocks()) : plan.doorGroups();
    }

    public ModulePlan plan() {
        return plan;
    }

    public List<BlockPosition> exitDoorBlocks() {
        return exitDoorBlocks;
    }

    public List<BlockPosition> lockDoorBlocks() {
        return java.util.stream.Stream.concat(entranceDoorBlocks.stream(), exitDoorBlocks.stream()).distinct().toList();
    }

    public List<List<BlockPosition>> doorGroups() { return doorGroups; }

    public boolean beginOpeningDoorGroup(int groupIndex) {
        return openedDoorGroups.add(groupIndex);
    }

    public boolean isDoorGroupOpen(int groupIndex) {
        return openedDoorGroups.contains(groupIndex);
    }

    public void doorButton(int groupIndex, BlockPosition position) {
        doorButtons.put(groupIndex, position);
    }

    public BlockPosition doorButton(int groupIndex) {
        return doorButtons.get(groupIndex);
    }

    public void removeDoorButton(int groupIndex) {
        doorButtons.remove(groupIndex);
    }

    public void captureClosedDoorBlock(BlockPosition position, String blockData) {
        closedDoorBlockData.put(position, blockData);
    }

    public boolean restoreClosedDoorBlock(World world, BlockPosition position) {
        String blockData = closedDoorBlockData.get(position);
        if (blockData == null) {
            return false;
        }
        try {
            world.getBlockAt(position.x(), position.y(), position.z()).setBlockData(Bukkit.createBlockData(blockData), false);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public com.nmdungeon.editor.WaveDefinition nextWave() {
        return nextWaveIndex < plan.waves().size() ? plan.waves().get(nextWaveIndex++) : null;
    }

    public boolean shouldSpawnBoss() {
        if (bossSpawned || plan.boss() == null) return false;
        bossSpawned = true;
        return true;
    }

    public void trackEncounterMob(java.util.UUID mobId) { aliveEncounterMobs.add(mobId); }
    public boolean removeEncounterMob(java.util.UUID mobId) { return aliveEncounterMobs.remove(mobId); }
    public boolean hasAliveEncounterMobs() { return !aliveEncounterMobs.isEmpty(); }
    public Set<java.util.UUID> aliveEncounterMobs() { return Set.copyOf(aliveEncounterMobs); }

    public Location respawnLocation(World world, Location fallback) {
        if (plan.checkpointPoint() != null) {
            return plan.checkpointPoint().toLocation(world);
        }
        return entryLocation(world, fallback);
    }

    public Location entryLocation(World world, Location fallback) {
        if (plan.playerEntryPoint() != null) {
            return plan.playerEntryPoint().toLocation(world);
        }
        if (entranceDoorBlocks.isEmpty()) {
            return fallback.clone();
        }
        Bounds bounds = plan.bounds();
        int averageX = (int) Math.round(entranceDoorBlocks.stream().mapToInt(BlockPosition::x).average().orElse(bounds.minX()));
        int averageZ = (int) Math.round(entranceDoorBlocks.stream().mapToInt(BlockPosition::z).average().orElse(bounds.minZ()));
        int lowestY = entranceDoorBlocks.stream().mapToInt(BlockPosition::y).min().orElse(bounds.minY() + 1);
        double x = averageX + 0.5;
        double z = averageZ + 0.5;
        float yaw = fallback.getYaw();
        if (averageX == bounds.minX()) {
            x += 1.0;
            yaw = -90.0F; // Enter from west: face east into the room.
        } else if (averageX == bounds.maxX()) {
            x -= 1.0;
            yaw = 90.0F; // Enter from east: face west into the room.
        } else if (averageZ == bounds.minZ()) {
            z += 1.0;
            yaw = 0.0F; // Enter from north: face south into the room.
        } else if (averageZ == bounds.maxZ()) {
            z -= 1.0;
            yaw = 180.0F; // Enter from south: face north into the room.
        }
        else return fallback.clone();
        return new Location(world, x, lowestY, z, yaw, fallback.getPitch());
    }

    public ModuleState state() {
        return state;
    }

    public void state(ModuleState state) {
        this.state = state;
    }
}
