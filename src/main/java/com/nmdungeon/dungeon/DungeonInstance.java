package com.nmdungeon.dungeon;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.map.MapView;
import org.bukkit.scheduler.BukkitTask;

public final class DungeonInstance {
    private final UUID id = UUID.randomUUID();
    private final UUID ownerId;
    private final Set<UUID> participantIds;
    private final int slotId;
    private final World world;
    private final DungeonPlan plan;
    private final String dungeonId;
    private final DungeonMapGenerator mapGenerator;
    private final int maxLives;
    private final int timeLimitSeconds;
    private final List<ModuleRuntime> modules;
    private ModuleRuntime respawnModule;
    private DungeonState state = DungeonState.REQUESTED;
    private boolean doorAnimationRunning;
    private final Set<BlockPosition> animatedDoorBlocks = ConcurrentHashMap.newKeySet();
    private final Set<Integer> revealedModuleIndices = ConcurrentHashMap.newKeySet();
    private MapView explorationMap;
    private int remainingLives;
    private long startedAtMillis;
    private BukkitTask timeLimitTask;

    public DungeonInstance(UUID ownerId, Set<UUID> participantIds, int slotId, World world, DungeonPlan plan,
                           String dungeonId, DungeonMapGenerator mapGenerator, int maxLives, int timeLimitSeconds) {
        this.ownerId = ownerId;
        this.participantIds = ConcurrentHashMap.newKeySet();
        this.participantIds.addAll(participantIds);
        this.slotId = slotId;
        this.world = world;
        this.plan = plan;
        this.dungeonId = dungeonId;
        this.mapGenerator = mapGenerator;
        this.maxLives = Math.max(0, maxLives);
        this.remainingLives = this.maxLives;
        this.timeLimitSeconds = Math.max(0, timeLimitSeconds);
        this.modules = plan.modules().stream().map(ModuleRuntime::new).collect(Collectors.toList());
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public Set<UUID> participantIds() {
        return Set.copyOf(participantIds);
    }

    public boolean removeParticipant(UUID playerId) {
        return participantIds.remove(playerId);
    }

    public int slotId() {
        return slotId;
    }

    public World world() {
        return world;
    }

    public DungeonPlan plan() {
        return plan;
    }

    public String dungeonId() {
        return dungeonId;
    }

    public DungeonMapGenerator mapGenerator() {
        return mapGenerator;
    }

    public int maxLives() { return maxLives; }
    public int remainingLives() { return remainingLives; }
    public boolean hasLifeLimit() { return maxLives > 0; }
    public int consumeLife() {
        if (!hasLifeLimit()) return -1;
        return --remainingLives;
    }

    public int timeLimitSeconds() { return timeLimitSeconds; }
    public boolean hasTimeLimit() { return timeLimitSeconds > 0; }
    public void startTimer() { startedAtMillis = System.currentTimeMillis(); }
    public int remainingTimeSeconds() {
        if (!hasTimeLimit()) return -1;
        if (startedAtMillis <= 0L) return timeLimitSeconds;
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
        return Math.max(0, (int) ((timeLimitSeconds * 1000L - elapsedMillis + 999L) / 1000L));
    }
    public BukkitTask timeLimitTask() { return timeLimitTask; }
    public void timeLimitTask(BukkitTask timeLimitTask) { this.timeLimitTask = timeLimitTask; }

    public List<ModuleRuntime> modules() {
        return modules;
    }

    public boolean reveal(ModuleRuntime module) {
        return module != null && revealedModuleIndices.add(module.plan().index());
    }

    public Set<Integer> revealedModuleIndices() {
        return Set.copyOf(revealedModuleIndices);
    }

    public MapView explorationMap() {
        return explorationMap;
    }

    public void explorationMap(MapView explorationMap) {
        this.explorationMap = explorationMap;
    }

    public ModuleRuntime respawnModule() {
        return respawnModule;
    }

    public void respawnModule(ModuleRuntime respawnModule) {
        this.respawnModule = respawnModule;
    }

    public DungeonState state() {
        return state;
    }

    public void state(DungeonState state) {
        this.state = state;
    }

    public boolean beginDoorAnimation() {
        if (doorAnimationRunning) {
            return false;
        }
        doorAnimationRunning = true;
        return true;
    }

    public void finishDoorAnimation() {
        doorAnimationRunning = false;
        animatedDoorBlocks.clear();
    }

    public void beginDoorAnimationBlocks(List<BlockPosition> blocks) {
        animatedDoorBlocks.clear();
        animatedDoorBlocks.addAll(blocks);
    }

    public boolean isAnimatedDoorBlock(Location location) {
        return location != null && animatedDoorBlocks.contains(new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    public ModuleRuntime findModule(Location location) {
        if (!location.getWorld().equals(world)) {
            return null;
        }
        return modules.stream().filter(module -> module.plan().bounds().contains(location)).findFirst().orElse(null);
    }
}
