package com.nmdungeon.dungeon;

import com.nmdungeon.NMDungeonPlugin;
import com.nmdungeon.editor.DungeonDefinition;
import com.nmdungeon.editor.DungeonRepository;
import com.nmdungeon.editor.SpawnPointDefinition;
import com.nmdungeon.editor.WaveDefinition;
import com.nmdungeon.editor.WaveSpawnDefinition;
import com.nmdungeon.event.DungeonCompleteEvent;
import com.nmdungeon.event.DungeonModuleClearEvent;
import com.nmdungeon.event.DungeonStartEvent;
import com.nmdungeon.message.MessageService;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class DungeonService {
    private final NMDungeonPlugin plugin;
    private DungeonMapGenerator mvpMapGenerator;
    private DungeonMapGenerator schematicMapGenerator;
    private final DungeonRepository repository;
    private final MessageService messages;
    private final MythicMobSpawner mythicMobSpawner = new MythicMobSpawner();
    private final PartiesBridge partiesBridge = new PartiesBridge();
    private final LucidMailboxBridge lucidMailbox;
    private final Map<UUID, DungeonInstance> instancesByPlayer = new HashMap<>();
    private final Map<UUID, EncounterOwner> encounterMobOwners = new HashMap<>();
    private final Map<UUID, BossBar> generationBossBars = new HashMap<>();
    private final AtomicInteger nextSlot = new AtomicInteger();
    private final AtomicInteger nextLoadTestSlot = new AtomicInteger(100_000);
    private final DungeonGenerationQueue generationQueue;
    private final NamespacedKey explorationMapInstanceKey;
    private int slotSize;
    private boolean loadTestRunning;

    public DungeonService(NMDungeonPlugin plugin, DungeonRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.messages = plugin.messages();
        this.lucidMailbox = new LucidMailboxBridge(plugin);
        this.slotSize = plugin.getConfig().getInt("slot-size", 256);
        int blocksPerTick = plugin.getConfig().getInt("blocks-per-tick", 6000);
        this.mvpMapGenerator = new TickBatchedMapGenerator(plugin, blocksPerTick);
        this.schematicMapGenerator = new FaweSchematicMapGenerator(plugin, repository, blocksPerTick);
        this.generationQueue = new DungeonGenerationQueue(plugin, plugin.getConfig().getInt("generation.max-concurrent", 1));
        this.explorationMapInstanceKey = new NamespacedKey(plugin, "exploration_map_instance");
    }

    public void reloadSettings() {
        this.slotSize = plugin.getConfig().getInt("slot-size", 256);
        int blocksPerTick = plugin.getConfig().getInt("blocks-per-tick", 6000);
        this.mvpMapGenerator = new TickBatchedMapGenerator(plugin, blocksPerTick);
        this.schematicMapGenerator = new FaweSchematicMapGenerator(plugin, repository, blocksPerTick);
        this.generationQueue.maxConcurrent(plugin.getConfig().getInt("generation.max-concurrent", 1));
        refreshGenerationBossBars();
    }

    public void start(Player player) {
        start(player, null);
    }

    public void start(Player player, String dungeonId) {
        PartiesBridge.PartyContext party = partiesBridge.findParty(player).orElse(null);
        if (party == null) {
            messages.send(player, "dungeon.party-required");
            return;
        }
        if (!party.leaderId().equals(player.getUniqueId())) {
            messages.send(player, "dungeon.leader-required");
            return;
        }
        List<UUID> offlineMembers = party.memberIds().stream().filter(memberId -> Bukkit.getPlayer(memberId) == null).toList();
        if (!offlineMembers.isEmpty()) {
            messages.send(player, "dungeon.offline-members", "count", String.valueOf(offlineMembers.size()));
            return;
        }
        if (party.memberIds().stream().anyMatch(instancesByPlayer::containsKey)) {
            messages.send(player, "dungeon.already-running");
            return;
        }

        World world = getOrCreateInstanceWorld();
        int slotId = nextSlot.getAndIncrement();
        DungeonPlan plan;
        DungeonMapGenerator generator;
        String instanceDungeonId = null;
        int maxLives = plugin.getConfig().getInt("default-limits.max-lives", 0);
        int timeLimitSeconds = plugin.getConfig().getInt("default-limits.time-limit-seconds", 0);
        try {
            if (dungeonId == null || dungeonId.isBlank()) {
                plan = DungeonPlanFactory.createMvpPlan(world, slotId * slotSize, 0);
                generator = mvpMapGenerator;
            } else {
                DungeonDefinition definition = repository.load(dungeonId);
                plan = SchematicDungeonPlanFactory.create(definition, repository, world, slotId * slotSize, 0,
                        player.getUniqueId().getMostSignificantBits() ^ (long) slotId);
                generator = schematicMapGenerator;
                instanceDungeonId = definition.id();
                maxLives = definition.maxLives();
                timeLimitSeconds = definition.timeLimitSeconds();
            }
        } catch (IOException | IllegalArgumentException exception) {
            messages.send(player, "dungeon.plan-failed", "reason", exception.getMessage());
            return;
        }
        DungeonInstance instance = new DungeonInstance(player.getUniqueId(), party.memberIds(), slotId, world, plan,
                instanceDungeonId, generator, maxLives, timeLimitSeconds);
        instance.state(DungeonState.GENERATING);
        party.memberIds().forEach(memberId -> instancesByPlayer.put(memberId, instance));
        messageParty(instance, "dungeon.generating");
        showGenerationProgress(instance, 0);

        generationQueue.submit(() -> generator.generate(instance, percent -> updateGenerationProgress(instance, percent)))
                .whenComplete((ignored, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                plugin.getLogger().warning("Dungeon generation failed: " + throwable.getMessage());
                instance.state(DungeonState.FAILED);
                hideGenerationProgress(instance);
                removeInstanceParticipants(instance);
                messageParty(instance, "dungeon.generation-failed");
                return;
            }
            if (!allParticipantsOnline(instance)) {
                finishInstance(instance, "파티원 중 누군가 생성 중 퇴장했습니다.");
                return;
            }
            instance.state(DungeonState.READY);
            prepareExplorationMap(instance);
            if (!teleportParty(instance, plan.startLocation())) {
                finishInstance(instance, "파티 입장 텔레포트에 실패했습니다.");
                return;
            }
            instance.state(DungeonState.RUNNING);
            instance.startTimer();
            startTimeLimit(instance);
            hideGenerationProgress(instance);
            messageParty(instance, "dungeon.generation-complete");
            Bukkit.getPluginManager().callEvent(new DungeonStartEvent(instance.dungeonId(), instance.participantIds()));
            activateModule(player, instance.findModule(player.getLocation()));
        }));
    }

    /** Starts the party that contains the supplied online player. Console callers may name any party member. */
    public void startForPartyMember(Player partyMember, String dungeonId) {
        PartiesBridge.PartyContext party = partiesBridge.findParty(partyMember).orElse(null);
        if (party == null) {
            messages.send(partyMember, "dungeon.party-required");
            return;
        }
        Player leader = Bukkit.getPlayer(party.leaderId());
        if (leader == null || !leader.isOnline()) {
            messages.send(partyMember, "dungeon.offline-members", "count", "1");
            return;
        }
        start(leader, dungeonId);
    }

    public void onPlayerMove(Player player, Location from, Location to) {
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        if (instance == null || instance.state() != DungeonState.RUNNING) {
            return;
        }
        ModuleRuntime module = instance.findModule(to);
        if (module == null) {
            return;
        }
        if (module.plan().type() == ModuleType.EXIT) {
            finishCompleted(instancesByPlayer.get(player.getUniqueId()));
            return;
        }
        activateModule(player, module);
    }

    /** Keeps the animated doorway closed logically without placing a real block inside its BlockDisplay. */
    public boolean isDoorAnimationBlocking(Player player, Location destination) {
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        return instance != null && instance.state() == DungeonState.RUNNING && instance.isAnimatedDoorBlock(destination);
    }

    public boolean onDoorButtonInteract(Player player, Block button) {
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        if (instance == null || instance.state() != DungeonState.RUNNING || !button.getWorld().equals(instance.world())) {
            return false;
        }
        if (!button.getType().name().endsWith("_BUTTON")) {
            return false;
        }
        for (ModuleRuntime module : instance.modules()) {
            if (module.state() != ModuleState.CLEARED) {
                continue;
            }
            for (int groupIndex = 0; groupIndex < module.doorGroups().size(); groupIndex++) {
                BlockPosition expectedButton = module.doorButton(groupIndex);
                if (module.isDoorGroupOpen(groupIndex) || expectedButton == null
                        || expectedButton.x() != button.getX() || expectedButton.y() != button.getY()
                        || expectedButton.z() != button.getZ()) {
                    continue;
                }
                ModuleRuntime destination = findConnectedModule(instance, module, module.doorGroups().get(groupIndex));
                if (destination != null && destination.plan().type() == ModuleType.BOSS
                        && instance.plan().requireAllModulesClearedForBoss() && !allPreBossModulesCleared(instance)) {
                    messages.actionBar(player, "dungeon.boss-door-requires-all-modules");
                    return true;
                }
                if (!instance.beginDoorAnimation()) {
                    messages.actionBar(player, "dungeon.door-busy");
                    return true;
                }
                if (module.beginOpeningDoorGroup(groupIndex)) {
                    // Consume the generated trigger immediately so it cannot be pressed again during the animation.
                    button.setType(Material.AIR, false);
                    module.removeDoorButton(groupIndex);
                    playDoorOpenAnimation(instance, module, groupIndex, module.doorGroups().get(groupIndex));
                    messages.actionBar(player, "dungeon.door-opening");
                } else {
                    instance.finishDoorAnimation();
                }
                return true;
            }
        }
        return false;
    }

    private boolean allPreBossModulesCleared(DungeonInstance instance) {
        return instance.modules().stream()
                .filter(module -> module.plan().type() != ModuleType.BOSS && module.plan().type() != ModuleType.EXIT)
                .allMatch(module -> module.state() == ModuleState.CLEARED);
    }

    public void onPlayerRespawn(Player player, org.bukkit.event.player.PlayerRespawnEvent event) {
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        if (instance == null || instance.state() != DungeonState.RUNNING) {
            return;
        }
        ModuleRuntime checkpoint = instance.respawnModule();
        if (checkpoint == null) {
            event.setRespawnLocation(instance.plan().startLocation());
            return;
        }
        event.setRespawnLocation(checkpoint.respawnLocation(instance.world(), instance.plan().startLocation()));
    }

    public void onPlayerDeath(Player player) {
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        if (instance == null || instance.state() != DungeonState.RUNNING || !instance.hasLifeLimit()) {
            return;
        }
        int remainingLives = instance.consumeLife();
        messageParty(instance, "dungeon.life-lost", "player", player.getName(), "lives", String.valueOf(Math.max(0, remainingLives)));
        if (remainingLives <= 0) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (instance.state() == DungeonState.RUNNING) {
                    finishInstance(instance, "파티의 던전 목숨을 모두 소진했습니다.");
                }
            });
        }
    }

    public void onPlayerQuit(Player player) {
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        if (instance == null || instance.state() == DungeonState.FREE || instance.state() == DungeonState.CLEANING) {
            return;
        }
        removePlayerFromGenerationProgress(instance, player);
        removeExplorationMap(player, instance);
        if (instance.ownerId().equals(player.getUniqueId())) {
            finishInstance(instance, "파티장이 던전 진행 중 퇴장하여 세션을 종료했습니다.");
            return;
        }
        instance.removeParticipant(player.getUniqueId());
        instancesByPlayer.remove(player.getUniqueId(), instance);
        if (!partiesBridge.removePlayerFromParty(player.getUniqueId())) {
            plugin.getLogger().warning("Could not remove quitting dungeon member from Parties: " + player.getName());
        }
        messageParty(instance, "dungeon.member-quit", "player", player.getName());
    }

    public void onEntityDeath(java.util.UUID entityId) {
        EncounterOwner owner = encounterMobOwners.remove(entityId);
        if (owner == null || owner.instance().state() != DungeonState.RUNNING) {
            return;
        }
        if (owner.module().removeEncounterMob(entityId) && !owner.module().hasAliveEncounterMobs()) {
            advanceEncounter(owner.instance(), owner.module());
        }
    }

    public boolean isRunningDungeonPlayer(Player player) {
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        return instance != null && instance.state() == DungeonState.RUNNING && player.getWorld().equals(instance.world());
    }

    public void clearCurrentModule(Player player) {
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        if (instance == null || instance.state() != DungeonState.RUNNING) {
            messages.send(player, "dungeon.no-instance");
            return;
        }
        ModuleRuntime module = instance.findModule(player.getLocation());
        if (module == null || module.state() != ModuleState.LOCKED) {
            messages.send(player, "dungeon.clear-wrong-room");
            return;
        }
        clearModule(instance, module);
        messages.send(player, "dungeon.clear-command-success");
    }

    /** Runs real FAWE schematic generation and cleanup without teleporting or registering players in a dungeon. */
    public void startLoadTest(Player player, String dungeonId, int requestedRuns) {
        if (loadTestRunning) {
            messages.send(player, "load-test.already-running");
            return;
        }
        int maxRuns = Math.max(1, plugin.getConfig().getInt("load-test.max-runs", 8));
        if (requestedRuns < 1 || requestedRuns > maxRuns) {
            messages.send(player, "load-test.invalid-runs", "max", String.valueOf(maxRuns));
            return;
        }

        DungeonDefinition definition;
        try {
            definition = repository.load(dungeonId);
        } catch (IOException exception) {
            messages.send(player, "load-test.definition-failed", "reason", exception.getMessage());
            return;
        }
        World world = getOrCreateInstanceWorld();
        List<DungeonInstance> jobs = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < requestedRuns; index++) {
                int slotId = nextLoadTestSlot.getAndIncrement();
                long seed = System.nanoTime() ^ ((long) slotId << 32) ^ player.getUniqueId().getLeastSignificantBits();
                DungeonPlan plan = SchematicDungeonPlanFactory.create(definition, repository, world, slotId * slotSize, 0, seed);
                jobs.add(new DungeonInstance(player.getUniqueId(), java.util.Set.of(), slotId, world, plan,
                        definition.id(), schematicMapGenerator, definition.maxLives(), definition.timeLimitSeconds()));
            }
        } catch (IllegalArgumentException exception) {
            messages.send(player, "load-test.plan-failed", "reason", exception.getMessage());
            return;
        }

        loadTestRunning = true;
        LoadTestReport report = new LoadTestReport(player.getUniqueId(), jobs.size(), System.nanoTime());
        messages.send(player, "load-test.started", "dungeon", definition.id(), "runs", String.valueOf(jobs.size()),
                "concurrent", String.valueOf(plugin.getConfig().getInt("generation.max-concurrent", 1)));
        jobs.forEach(instance -> runLoadTestJob(instance, report));
    }

    private void runLoadTestJob(DungeonInstance instance, LoadTestReport report) {
        long queuedAt = System.nanoTime();
        generationQueue.submit(() -> instance.mapGenerator().generate(instance, ignored -> { })).whenComplete((ignored, generationFailure) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    long generationMillis = elapsedMillis(queuedAt);
                    if (generationFailure != null) {
                        completeLoadTestJob(report, generationMillis, 0L, generationFailure);
                        return;
                    }
                    long cleanupAt = System.nanoTime();
                    generationQueue.submitCleanup(() -> instance.mapGenerator().clear(instance)).whenComplete((cleared, cleanupFailure) ->
                            Bukkit.getScheduler().runTask(plugin, () -> completeLoadTestJob(report, generationMillis,
                                    elapsedMillis(cleanupAt), cleanupFailure)));
                }));
    }

    private void completeLoadTestJob(LoadTestReport report, long generationMillis, long cleanupMillis, Throwable failure) {
        report.complete(generationMillis, cleanupMillis, failure != null);
        if (report.completed() < report.total()) {
            return;
        }
        loadTestRunning = false;
        Player requester = Bukkit.getPlayer(report.requesterId());
        if (requester != null && requester.isOnline()) {
            messages.send(requester, "load-test.complete", "runs", String.valueOf(report.total()),
                    "failed", String.valueOf(report.failed()), "generation-ms", String.valueOf(report.averageGenerationMillis()),
                    "cleanup-ms", String.valueOf(report.averageCleanupMillis()), "total-ms", String.valueOf(elapsedMillis(report.startedAt())));
        }
    }

    public void showStatus(Player player) {
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        if (instance == null) {
            messages.send(player, "dungeon.no-status-instance");
            return;
        }
        ModuleRuntime module = instance.findModule(player.getLocation());
        String moduleInfo = module == null ? "외부" : module.plan().type() + " / " + module.state();
        messages.send(player, "dungeon.status", "state", instance.state().name(), "slot", String.valueOf(instance.slotId()));
        messages.send(player, "dungeon.status-module", "module", moduleInfo);
        messages.send(player, "dungeon.status-limits", "lives", formatLives(instance), "max-lives", formatMaxLives(instance),
                "time", formatTime(instance.remainingTimeSeconds()), "max-time", formatMaxTime(instance));
    }

    public void finish(Player player, String reason) {
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        if (instance == null) {
            messages.send(player, "dungeon.no-instance");
            return;
        }
        finishInstance(instance, reason);
    }

    /** Whether a saved dungeon definition is currently generating, running, or being cleaned up. */
    public boolean isDungeonActive(String dungeonId) {
        return dungeonId != null && new HashSet<>(instancesByPlayer.values()).stream()
                .anyMatch(instance -> dungeonId.equalsIgnoreCase(instance.dungeonId()) && instance.state() != DungeonState.FREE);
    }

    public void shutdown() {
        for (DungeonInstance instance : new HashSet<>(instancesByPlayer.values())) {
            finishInstance(instance, "서버가 종료됩니다.");
        }
    }

    /** PlaceholderAPI bridge. Returns an empty string for unsupported identifiers or players outside a dungeon. */
    public String placeholder(Player player, String identifier) {
        DungeonInstance instance = player == null ? null : instancesByPlayer.get(player.getUniqueId());
        String key = identifier == null ? "" : identifier.toLowerCase(java.util.Locale.ROOT);
        if ("active".equals(key)) return instance != null && instance.state() == DungeonState.RUNNING ? "true" : "false";
        if (instance == null) return "";
        return switch (key) {
            case "id", "dungeon_id" -> instance.dungeonId() == null ? "mvp" : instance.dungeonId();
            case "state" -> instance.state().name();
            case "lives", "lives_remaining" -> formatLives(instance);
            case "lives_max" -> formatMaxLives(instance);
            case "time", "time_remaining" -> formatTime(instance.remainingTimeSeconds());
            case "time_remaining_seconds" -> String.valueOf(instance.remainingTimeSeconds());
            case "time_limit", "time_limit_seconds" -> String.valueOf(instance.timeLimitSeconds());
            default -> "";
        };
    }

    private void activateModule(Player player, ModuleRuntime module) {
        if (module == null || module.state() != ModuleState.INACTIVE) {
            return;
        }
        module.state(ModuleState.ACTIVE);
        DungeonInstance instance = instancesByPlayer.get(player.getUniqueId());
        if (instance != null) {
            instance.respawnModule(module);
            instance.reveal(module);
        }
        String moduleName = switch (module.plan().type()) {
            case ENTRANCE -> "입구";
            case CORRIDOR -> "길";
            case ROOM -> "전투방";
            case BOSS -> "보스방";
            case EXIT -> "출구";
        };
        if (instance != null) {
            messageParty(instance, "dungeon.module-enter", "module", moduleName);
        }

        if (module.plan().type().requiresClearCondition()) {
            module.state(ModuleState.LOCKED);
            teleportPartyInsideThenLock(instance, module, () -> startEncounter(instance, module));
            if (instance != null) messageParty(instance, "dungeon.room-locked");
            return;
        }
        if (instance != null) {
            clearModule(instance, module);
        }
    }

    private void clearModule(DungeonInstance instance, ModuleRuntime module) {
        if (module.state() == ModuleState.CLEARED) {
            return;
        }
        module.state(ModuleState.CLEARED);
        Bukkit.getPluginManager().callEvent(new DungeonModuleClearEvent(instance.dungeonId(), module.plan().sourceModuleId(), instance.participantIds()));
        grantRewards(instance, module.plan().clearRewards(), module.plan().clearRewardChances(),
                "모듈 " + module.plan().sourceModuleId() + " 클리어 보상");
        runModuleClearCommands(instance, module.plan());
        // The route the player used to enter is safe to reopen immediately. Forward doors require their button.
        for (int groupIndex = 0; groupIndex < module.doorGroups().size(); groupIndex++) {
            List<BlockPosition> group = module.doorGroups().get(groupIndex);
            boolean isEntranceGroup = group.stream().anyMatch(module.plan().entranceDoorwayBlocks()::contains);
            if (isEntranceGroup && module.beginOpeningDoorGroup(groupIndex)) {
                openDoorImmediately(instance, group);
            } else if (!isEntranceGroup) {
                installDoorButton(instance, module, groupIndex, group);
            }
        }
    }

    private void runModuleClearCommands(DungeonInstance instance, ModulePlan plan) {
        if (plan.clearCommands() == null || plan.clearCommands().isEmpty()) return;
        for (Player participant : onlineParticipants(instance)) {
            for (String template : plan.clearCommands()) {
                if (template == null) continue;
                String command = template.replace("{player}", participant.getName()).trim();
                if (command.startsWith("/")) command = command.substring(1).trim();
                if (command.isEmpty()) continue;
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Module clear command failed for " + plan.sourceModuleId()
                            + ": " + command + " (" + exception.getMessage() + ")");
                }
            }
        }
    }

    private void installDoorButton(DungeonInstance instance, ModuleRuntime module, int groupIndex,
                                   java.util.List<BlockPosition> doorBlocks) {
        if (module.doorButton(groupIndex) != null || doorBlocks.isEmpty()) {
            return;
        }
        Bounds bounds = module.plan().bounds();
        int centerX = (int) Math.round(doorBlocks.stream().mapToInt(BlockPosition::x).average().orElse(bounds.minX()));
        int centerY = (int) Math.round(doorBlocks.stream().mapToInt(BlockPosition::y).average().orElse(bounds.minY() + 1));
        int centerZ = (int) Math.round(doorBlocks.stream().mapToInt(BlockPosition::z).average().orElse(bounds.minZ()));

        int northDistance = Math.abs(centerZ - bounds.minZ());
        int southDistance = Math.abs(bounds.maxZ() - centerZ);
        int westDistance = Math.abs(centerX - bounds.minX());
        int eastDistance = Math.abs(bounds.maxX() - centerX);
        int nearestBoundary = Math.min(Math.min(northDistance, southDistance), Math.min(westDistance, eastDistance));
        BlockPosition buttonPosition;
        BlockFace facing;
        if (nearestBoundary == northDistance) {
            buttonPosition = new BlockPosition(centerX, centerY, centerZ + 1);
            facing = BlockFace.SOUTH;
        } else if (nearestBoundary == southDistance) {
            buttonPosition = new BlockPosition(centerX, centerY, centerZ - 1);
            facing = BlockFace.NORTH;
        } else if (nearestBoundary == westDistance) {
            buttonPosition = new BlockPosition(centerX + 1, centerY, centerZ);
            facing = BlockFace.EAST;
        } else {
            buttonPosition = new BlockPosition(centerX - 1, centerY, centerZ);
            facing = BlockFace.WEST;
        }
        Block buttonBlock = instance.world().getBlockAt(buttonPosition.x(), buttonPosition.y(), buttonPosition.z());
        if (!buttonBlock.getType().isAir()) {
            plugin.getLogger().warning("Door button location is occupied for module " + module.plan().sourceModuleId()
                    + " at " + buttonPosition + ". Keep the inner center block empty.");
            return;
        }
        BlockData buttonData = Material.STONE_BUTTON.createBlockData();
        if (buttonData instanceof Directional directional) {
            directional.setFacing(facing);
        }
        buttonBlock.setBlockData(buttonData, false);
        module.doorButton(groupIndex, buttonPosition);
    }

    private void openDoorImmediately(DungeonInstance instance, java.util.List<BlockPosition> doorBlocks) {
        for (BlockPosition position : doorBlocks) {
            instance.world().getBlockAt(position.x(), position.y(), position.z()).setType(Material.AIR, false);
        }
    }

    private void playDoorOpenAnimation(DungeonInstance instance, ModuleRuntime module, int groupIndex,
                                       java.util.List<BlockPosition> doorBlocks) {
        java.util.List<AnimatedDoorBlock> displays = new java.util.ArrayList<>();
        plugin.sounds().playDoorOpening(onlineParticipants(instance), doorSoundLocation(instance.world(), doorBlocks));
        instance.beginDoorAnimationBlocks(doorBlocks);
        DoorAnimationAxis axis = doorAnimationAxis(module, doorBlocks);
        for (BlockPosition position : doorBlocks) {
            Block block = instance.world().getBlockAt(position.x(), position.y(), position.z());
            if (block.getType().isAir()) {
                continue;
            }
            BlockData original = block.getBlockData().clone();
            // A Barrier at this exact location darkens the overlapping BlockDisplay client-side.
            // Keep the world cell empty and enforce collision through PlayerMoveEvent instead.
            block.setType(Material.AIR, false);
            BlockDisplay display = instance.world().spawn(block.getLocation(), BlockDisplay.class, entity -> {
                entity.setBlock(original);
                entity.setTeleportDuration(1);
                entity.setShadowRadius(0.0F);
                entity.setShadowStrength(0.0F);
                entity.setBrightness(new Display.Brightness(15, 15));
            });
            displays.add(new AnimatedDoorBlock(display, block.getLocation(), original));
        }
        // A three-block descent crosses the authored floor/structure beneath the doorway.
        // Temporarily clear only that visual path, then restore it exactly after the animation.
        java.util.List<AnimationPathBlock> clearedPath = clearDoorAnimationPath(instance.world(), doorBlocks);
        new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                if (tick < 10) { // 0.5 seconds: a locked door strains in every direction.
                    shakeDoor(instance.world(), displays, tick, axis);
                    tick++;
                    return;
                }
                if (tick < 24) { // 0.7 seconds: 14 ticks of visibly progressive descent.
                    lowerDoor(instance.world(), displays, tick - 10, axis);
                    tick++;
                    return;
                }
                openDoorImmediately(instance, doorBlocks);
                plugin.sounds().playDoorOpened(onlineParticipants(instance), doorSoundLocation(instance.world(), doorBlocks));
                displays.forEach(animated -> animated.display().remove());
                restoreDoorAnimationPath(instance.world(), clearedPath);
                instance.finishDoorAnimation();
                // Let the open doorway be visible briefly, then move the party into the connected module.
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> movePartyThroughOpenedDoor(instance, module, doorBlocks), 20L);
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Location doorSoundLocation(World world, java.util.List<BlockPosition> doorBlocks) {
        if (doorBlocks.isEmpty()) return world.getSpawnLocation();
        return new Location(world,
                doorBlocks.stream().mapToInt(BlockPosition::x).average().orElse(0.0D) + 0.5D,
                doorBlocks.stream().mapToInt(BlockPosition::y).average().orElse(0.0D) + 0.5D,
                doorBlocks.stream().mapToInt(BlockPosition::z).average().orElse(0.0D) + 0.5D);
    }

    private void shakeDoor(World world, java.util.List<AnimatedDoorBlock> blocks, int tick, DoorAnimationAxis axis) {
        // The large movement is only toward the open passage. Moving sideways/upward makes outer door
        // blocks intersect the fixed frame, which creates the dark client-side overlap artifact.
        double amplitude = 0.05D + (tick % 4) * 0.015D;
        double direction = (tick & 1) == 0 ? 1.0D : -1.0D;
        double x = axis.normalX() * direction * amplitude;
        double z = axis.normalZ() * direction * amplitude;
        // A tiny vertical component keeps the heavy vibration feel without entering the top/bottom frame.
        double y = ((tick % 3) - 1) * 0.008D;
        for (AnimatedDoorBlock animated : blocks) {
            if (!animated.display().isValid()) continue;
            animated.display().teleport(animated.base().clone().add(x, y, z));
            if ((tick & 1) == 0) spawnBreakParticles(world, animated, 4);
        }
    }

    private void lowerDoor(World world, java.util.List<AnimatedDoorBlock> blocks, int fallTick, DoorAnimationAxis axis) {
        double descent = 3.0D * (fallTick + 1) / 14.0D;
        double outward = 0.08D;
        for (AnimatedDoorBlock animated : blocks) {
            if (!animated.display().isValid()) continue;
            animated.display().teleport(animated.base().clone().add(axis.normalX() * outward, -descent, axis.normalZ() * outward));
            if (fallTick % 3 == 0) spawnBreakParticles(world, animated, 2);
        }
    }

    private void spawnBreakParticles(World world, AnimatedDoorBlock animated, int count) {
        Location center = animated.base().clone().add(0.5D, 0.5D, 0.5D);
        world.spawnParticle(Particle.BLOCK, center, count, 0.34D, 0.34D, 0.34D, 0.05D, animated.blockData());
    }

    private DoorAnimationAxis doorAnimationAxis(ModuleRuntime module, java.util.List<BlockPosition> doorBlocks) {
        Bounds bounds = module.plan().bounds();
        double x = doorBlocks.stream().mapToInt(BlockPosition::x).average().orElse(bounds.minX());
        double z = doorBlocks.stream().mapToInt(BlockPosition::z).average().orElse(bounds.minZ());
        int north = Math.abs((int) Math.round(z) - bounds.minZ());
        int south = Math.abs(bounds.maxZ() - (int) Math.round(z));
        int west = Math.abs((int) Math.round(x) - bounds.minX());
        int east = Math.abs(bounds.maxX() - (int) Math.round(x));
        int closest = Math.min(Math.min(north, south), Math.min(west, east));
        if (closest == north) return new DoorAnimationAxis(0.0D, -1.0D);
        if (closest == south) return new DoorAnimationAxis(0.0D, 1.0D);
        if (closest == west) return new DoorAnimationAxis(-1.0D, 0.0D);
        return new DoorAnimationAxis(1.0D, 0.0D);
    }

    private java.util.List<AnimationPathBlock> clearDoorAnimationPath(World world, java.util.List<BlockPosition> doorBlocks) {
        java.util.Set<BlockPosition> doorSet = new java.util.HashSet<>(doorBlocks);
        java.util.Map<BlockPosition, BlockData> original = new java.util.LinkedHashMap<>();
        for (BlockPosition door : doorBlocks) {
            for (int depth = 1; depth <= 3; depth++) {
                BlockPosition path = new BlockPosition(door.x(), door.y() - depth, door.z());
                if (doorSet.contains(path) || original.containsKey(path)) continue;
                Block block = world.getBlockAt(path.x(), path.y(), path.z());
                if (!block.getType().isAir()) {
                    original.put(path, block.getBlockData().clone());
                    block.setType(Material.AIR, false);
                }
            }
        }
        return original.entrySet().stream().map(entry -> {
            BlockPosition position = entry.getKey();
            BlockDisplay display = world.spawn(new Location(world, position.x(), position.y(), position.z()), BlockDisplay.class, entity -> {
                entity.setBlock(entry.getValue());
                entity.setShadowRadius(0.0F);
                entity.setShadowStrength(0.0F);
                entity.setBrightness(new Display.Brightness(15, 15));
            });
            return new AnimationPathBlock(position, entry.getValue(), display);
        }).toList();
    }

    private void restoreDoorAnimationPath(World world, java.util.List<AnimationPathBlock> pathBlocks) {
        for (AnimationPathBlock path : pathBlocks) {
            if (path.display().isValid()) {
                path.display().remove();
            }
            world.getBlockAt(path.position().x(), path.position().y(), path.position().z()).setBlockData(path.blockData(), false);
        }
    }

    private void movePartyThroughOpenedDoor(DungeonInstance instance, ModuleRuntime source,
                                            java.util.List<BlockPosition> openedDoorBlocks) {
        if (instance.state() != DungeonState.RUNNING) {
            return;
        }
        if (!allParticipantsOnline(instance)) {
            finishInstance(instance, "파티원 중 누군가 던전에서 퇴장했습니다.");
            return;
        }
        ModuleRuntime destination = findConnectedModule(instance, source, openedDoorBlocks);
        if (destination == null) {
            plugin.getLogger().warning("Could not find a destination module for an opened door in "
                    + source.plan().sourceModuleId());
            return;
        }
        if (destination.plan().type() == ModuleType.EXIT) {
            finishCompleted(instance);
            return;
        }
        // Players who already walked in during the one-second grace period stay put; the rest join them.
        List<Player> outside = onlineParticipants(instance).stream()
                .filter(player -> !destination.plan().bounds().contains(player.getLocation())).toList();
        if (!outside.isEmpty()) {
            teleportPlayers(outside, destination.entryLocation(instance.world(), instance.plan().startLocation()));
        }
        Player leader = Bukkit.getPlayer(instance.ownerId());
        if (leader != null) activateModule(leader, destination);
    }

    private ModuleRuntime findConnectedModule(DungeonInstance instance, ModuleRuntime source,
                                              java.util.List<BlockPosition> openedDoorBlocks) {
        return instance.modules().stream()
                .filter(candidate -> candidate != source && !candidate.plan().entranceDoorwayBlocks().isEmpty())
                .map(candidate -> new ModuleDistance(candidate, closestHorizontalDistance(openedDoorBlocks,
                        candidate.plan().entranceDoorwayBlocks())))
                .filter(match -> match.distance() <= 6)
                .min(java.util.Comparator.comparingInt(ModuleDistance::distance))
                .map(ModuleDistance::module)
                .orElse(null);
    }

    private int closestHorizontalDistance(java.util.List<BlockPosition> first, java.util.List<BlockPosition> second) {
        int closest = Integer.MAX_VALUE;
        for (BlockPosition a : first) {
            for (BlockPosition b : second) {
                int distance = Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) + Math.abs(a.y() - b.y());
                closest = Math.min(closest, distance);
            }
        }
        return closest;
    }

    private record ModuleDistance(ModuleRuntime module, int distance) {
    }

    private void lockModule(DungeonInstance instance, ModuleRuntime module) {
        if (instance == null) {
            return;
        }
        for (BlockPosition position : module.lockDoorBlocks()) {
            if (!module.restoreClosedDoorBlock(instance.world(), position)) {
                // The fixed-block MVP has no schematic state to restore.
                instance.world().getBlockAt(position.x(), position.y(), position.z()).setType(Material.IRON_BARS, false);
            }
        }
    }

    private void teleportPartyInsideThenLock(DungeonInstance instance, ModuleRuntime module, Runnable afterLock) {
        if (instance == null) {
            return;
        }
        if (teleportParty(instance, module.entryLocation(instance.world(), instance.plan().startLocation()))) {
            lockModule(instance, module);
            afterLock.run();
            return;
        }
        finishInstance(instance, "방 입장 텔레포트에 실패했습니다.");
    }

    private void startEncounter(DungeonInstance instance, ModuleRuntime module) {
        if (module.plan().waves().isEmpty() && module.plan().boss() == null) {
            messageParty(instance, "dungeon.no-waves");
            return;
        }
        advanceEncounter(instance, module);
    }

    private void advanceEncounter(DungeonInstance instance, ModuleRuntime module) {
        if (module.state() != ModuleState.LOCKED || module.hasAliveEncounterMobs()) return;
        WaveDefinition wave = module.nextWave();
        if (wave != null) {
            int spawned = spawnWave(instance, module, wave);
            if (spawned > 0) {
                messageParty(instance, "dungeon.wave-start", "wave", wave.id(), "count", String.valueOf(spawned));
                return;
            }
        }
        if (module.shouldSpawnBoss()) {
            int spawned = spawnMythic(instance, module, module.plan().boss().spawnPointId(), module.plan().boss().mythicMobId(), 1,
                    module.plan().boss().level());
            if (spawned > 0) {
                messageParty(instance, "dungeon.boss-spawn");
                return;
            }
        }
        clearModule(instance, module);
        messageParty(instance, "dungeon.encounter-complete");
    }

    private int spawnWave(DungeonInstance instance, ModuleRuntime module, WaveDefinition wave) {
        int spawned = 0;
        for (WaveSpawnDefinition spawn : wave.spawns()) {
            spawned += spawnMythic(instance, module, spawn.spawnPointId(), spawn.mythicMobId(), spawn.amount(), spawn.level());
        }
        return spawned;
    }

    private int spawnMythic(DungeonInstance instance, ModuleRuntime module, String spawnPointId, String mobId, int amount, double level) {
        SpawnPointDefinition point = module.plan().spawnPoints().stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(spawnPointId)).findFirst().orElse(null);
        if (point == null || Bukkit.getPluginManager().getPlugin("MythicMobs") == null) {
            plugin.getLogger().warning("Encounter spawn skipped: missing spawn point or MythicMobs: " + spawnPointId + "/" + mobId);
            return 0;
        }
        BlockPosition position = module.plan().toWorldPosition(point.position());
        Location location = new Location(instance.world(), position.x() + 0.5, position.y() + 1, position.z() + 0.5);
        int spawned = 0;
        for (int index = 0; index < amount; index++) {
            java.util.Optional<java.util.UUID> spawnedMob = mythicMobSpawner.spawn(mobId, location, level);
            if (spawnedMob.isPresent()) {
                module.trackEncounterMob(spawnedMob.get());
                encounterMobOwners.put(spawnedMob.get(), new EncounterOwner(instance, module));
                spawned++;
            }
        }
        if (spawned == 0) plugin.getLogger().warning("Encounter spawn skipped: MythicMob not found or spawn failed: " + mobId);
        return spawned;
    }

    private void finishInstance(DungeonInstance instance, String reason) {
        if (instance.state() == DungeonState.FINISHING || instance.state() == DungeonState.CLEANING
                || instance.state() == DungeonState.FREE) {
            return;
        }
        instance.state(DungeonState.FINISHING);
        stopTimeLimit(instance);
        hideGenerationProgress(instance);
        removeEncounterMobs(instance);
        for (Player participant : onlineParticipants(instance)) {
            removeExplorationMap(participant, instance);
            participant.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
            messages.send(participant, "dungeon.session-finished", "reason", reason);
        }
        instance.state(DungeonState.CLEANING);
        generationQueue.submitCleanup(() -> instance.mapGenerator().clear(instance)).whenComplete((ignored, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                plugin.getLogger().warning("Dungeon cleanup failed: " + throwable.getMessage());
                instance.state(DungeonState.FAILED);
            } else {
                instance.state(DungeonState.FREE);
            }
            removeInstanceParticipants(instance);
        }));
    }

    private void finishCompleted(DungeonInstance instance) {
        if (instance == null || instance.state() != DungeonState.RUNNING) return;
        Bukkit.getPluginManager().callEvent(new DungeonCompleteEvent(instance.dungeonId(), instance.participantIds()));
        grantRewards(instance, instance.plan().clearRewards(), instance.plan().clearRewardChances(),
                "던전 " + (instance.dungeonId() == null ? "MVP" : instance.dungeonId()) + " 완료 보상");
        finishInstance(instance, "던전을 완료했습니다.");
    }

    private void grantRewards(DungeonInstance instance, List<ItemStack> rewards, List<Double> chances, String source) {
        if (rewards == null || rewards.isEmpty()) return;
        String mailSource = source.length() > 80 ? source.substring(0, 80) : source;
        for (Player participant : onlineParticipants(instance)) {
            int mailed = 0;
            int direct = 0;
            for (int index = 0; index < rewards.size(); index++) {
                ItemStack reward = rewards.get(index);
                double chance = index < chances.size() ? chances.get(index) : 100.0D;
                if (chance <= 0.0D || ThreadLocalRandom.current().nextDouble(100.0D) >= chance) continue;
                if (lucidMailbox.send(participant.getUniqueId(), reward, mailSource)) {
                    mailed++;
                    continue;
                }
                Map<Integer, ItemStack> overflow = participant.getInventory().addItem(reward.clone());
                overflow.values().forEach(item -> participant.getWorld().dropItemNaturally(participant.getLocation(), item));
                direct++;
            }
            int delivered = mailed + direct;
            if (delivered > 0) {
                messages.send(participant, "dungeon.reward-delivered", "count", String.valueOf(delivered));
            }
        }
    }

    private List<Player> onlineParticipants(DungeonInstance instance) {
        return instance.participantIds().stream().map(Bukkit::getPlayer).filter(player -> player != null && player.isOnline()).toList();
    }

    private boolean allParticipantsOnline(DungeonInstance instance) {
        return onlineParticipants(instance).size() == instance.participantIds().size();
    }

    private void messageParty(DungeonInstance instance, String key, String... replacements) {
        onlineParticipants(instance).forEach(participant -> messages.send(participant, key, replacements));
    }

    private boolean teleportParty(DungeonInstance instance, Location center) {
        return teleportPlayers(onlineParticipants(instance), center) && allParticipantsOnline(instance);
    }

    private boolean teleportPlayers(List<Player> players, Location center) {
        for (int index = 0; index < players.size(); index++) {
            Player participant = players.get(index);
            double offsetX = (index % 3 - 1) * 0.7D;
            double offsetZ = (index / 3) * 0.7D;
            if (!participant.teleport(center.clone().add(offsetX, 0.0D, offsetZ))) {
                return false;
            }
        }
        return true;
    }

    private void removeInstanceParticipants(DungeonInstance instance) {
        instance.participantIds().forEach(participantId -> instancesByPlayer.remove(participantId, instance));
    }

    private void prepareExplorationMap(DungeonInstance instance) {
        if (!plugin.getConfig().getBoolean("exploration-map.enabled", true)) {
            return;
        }
        MapView mapView = Bukkit.createMap(instance.world());
        for (MapRenderer renderer : mapView.getRenderers()) {
            mapView.removeRenderer(renderer);
        }
        mapView.addRenderer(new DungeonExplorationMap(instance));
        instance.explorationMap(mapView);
        for (Player participant : onlineParticipants(instance)) {
            ItemStack map = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) map.getItemMeta();
            meta.setMapView(mapView);
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    plugin.getConfig().getString("exploration-map.item-name", "&a탐험 지도")));
            meta.getPersistentDataContainer().set(explorationMapInstanceKey, PersistentDataType.STRING, instance.id().toString());
            map.setItemMeta(meta);
            Map<Integer, ItemStack> overflow = participant.getInventory().addItem(map);
            overflow.values().forEach(item -> participant.getWorld().dropItemNaturally(participant.getLocation(), item));
            messages.send(participant, "dungeon.exploration-map-received");
        }
    }

    private void removeExplorationMap(Player player, DungeonInstance instance) {
        String expectedId = instance.id().toString();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isExplorationMapFor(item, expectedId)) {
                player.getInventory().setItem(slot, null);
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isExplorationMapFor(offHand, expectedId)) {
            player.getInventory().setItemInOffHand(null);
        }
        ItemStack cursor = player.getItemOnCursor();
        if (isExplorationMapFor(cursor, expectedId)) {
            player.setItemOnCursor(null);
        }
    }

    private boolean isExplorationMapFor(ItemStack item, String instanceId) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) {
            return false;
        }
        String markedInstance = item.getItemMeta().getPersistentDataContainer()
                .get(explorationMapInstanceKey, PersistentDataType.STRING);
        return instanceId.equals(markedInstance);
    }

    private void startTimeLimit(DungeonInstance instance) {
        if (!instance.hasTimeLimit()) return;
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (instance.state() != DungeonState.RUNNING) {
                    cancel();
                    return;
                }
                if (instance.remainingTimeSeconds() <= 0) {
                    cancel();
                    finishInstance(instance, "던전 제한 시간이 종료되었습니다.");
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
        instance.timeLimitTask(task);
    }

    private void stopTimeLimit(DungeonInstance instance) {
        BukkitTask task = instance.timeLimitTask();
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        instance.timeLimitTask(null);
    }

    private String formatLives(DungeonInstance instance) {
        return instance.hasLifeLimit() ? String.valueOf(Math.max(0, instance.remainingLives())) : "∞";
    }

    private String formatMaxLives(DungeonInstance instance) {
        return instance.hasLifeLimit() ? String.valueOf(instance.maxLives()) : "∞";
    }

    private String formatTime(int seconds) {
        if (seconds < 0) return "∞";
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private String formatMaxTime(DungeonInstance instance) {
        return instance.hasTimeLimit() ? formatTime(instance.timeLimitSeconds()) : "∞";
    }

    private void updateGenerationProgress(DungeonInstance instance, int percent) {
        Bukkit.getScheduler().runTask(plugin, () -> showGenerationProgress(instance, percent));
    }

    private void showGenerationProgress(DungeonInstance instance, int percent) {
        if (!plugin.getConfig().getBoolean("generation-bossbar.enabled", true)) {
            hideGenerationProgress(instance);
            return;
        }
        if (instance.state() != DungeonState.GENERATING) {
            return;
        }
        int clampedPercent = Math.max(0, Math.min(100, percent));
        BossBar bar = generationBossBars.computeIfAbsent(instance.id(), ignored ->
                Bukkit.createBossBar("", generationBossBarColor(), generationBossBarStyle()));
        String title = plugin.getConfig().getString("generation-bossbar.title", "&e맵 생성중 (&f{percent}%&e)")
                .replace("{percent}", String.valueOf(clampedPercent));
        bar.setTitle(ChatColor.translateAlternateColorCodes('&', title));
        bar.setColor(generationBossBarColor());
        bar.setStyle(generationBossBarStyle());
        bar.setProgress(Math.max(0.0D, Math.min(1.0D, clampedPercent / 100.0D)));
        for (Player participant : onlineParticipants(instance)) {
            if (!bar.getPlayers().contains(participant)) {
                bar.addPlayer(participant);
            }
        }
        bar.setVisible(true);
    }

    private BarColor generationBossBarColor() {
        try {
            return BarColor.valueOf(plugin.getConfig().getString("generation-bossbar.color", "YELLOW").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return BarColor.YELLOW;
        }
    }

    private BarStyle generationBossBarStyle() {
        try {
            return BarStyle.valueOf(plugin.getConfig().getString("generation-bossbar.style", "SOLID").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return BarStyle.SOLID;
        }
    }

    private void removePlayerFromGenerationProgress(DungeonInstance instance, Player player) {
        BossBar bar = generationBossBars.get(instance.id());
        if (bar != null) {
            bar.removePlayer(player);
        }
    }

    private void hideGenerationProgress(DungeonInstance instance) {
        BossBar bar = generationBossBars.remove(instance.id());
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
    }

    private void refreshGenerationBossBars() {
        if (!plugin.getConfig().getBoolean("generation-bossbar.enabled", true)) {
            generationBossBars.values().forEach(bar -> {
                bar.removeAll();
                bar.setVisible(false);
            });
            generationBossBars.clear();
            return;
        }
        for (DungeonInstance instance : new HashSet<>(instancesByPlayer.values())) {
            if (instance.state() != DungeonState.GENERATING) continue;
            BossBar current = generationBossBars.get(instance.id());
            int percent = current == null ? 0 : (int) Math.round(current.getProgress() * 100.0D);
            showGenerationProgress(instance, percent);
        }
    }

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void removeEncounterMobs(DungeonInstance instance) {
        for (ModuleRuntime module : instance.modules()) {
            for (UUID mobId : module.aliveEncounterMobs()) {
                encounterMobOwners.remove(mobId);
                org.bukkit.entity.Entity entity = Bukkit.getEntity(mobId);
                if (entity != null && entity.isValid()) {
                    entity.remove();
                }
            }
        }
    }

    private World getOrCreateInstanceWorld() {
        String name = plugin.getConfig().getString("instance-world", "nm_dungeon_instances");
        World world = Bukkit.getWorld(name);
        if (world == null) {
            world = Bukkit.createWorld(new WorldCreator(name).generator(new VoidChunkGenerator()));
        }
        if (world == null) {
            throw new IllegalStateException("인스턴스 월드를 생성할 수 없습니다: " + name);
        }
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setAutoSave(false);
        return world;
    }

    private record AnimatedDoorBlock(BlockDisplay display, Location base, BlockData blockData) {
    }

    private record AnimationPathBlock(BlockPosition position, BlockData blockData, BlockDisplay display) {
    }

    private record DoorAnimationAxis(double normalX, double normalZ) {
    }

    private record EncounterOwner(DungeonInstance instance, ModuleRuntime module) {
    }

    private static final class LoadTestReport {
        private final UUID requesterId;
        private final int total;
        private final long startedAt;
        private int completed;
        private int failed;
        private long generationMillis;
        private long cleanupMillis;

        private LoadTestReport(UUID requesterId, int total, long startedAt) {
            this.requesterId = requesterId;
            this.total = total;
            this.startedAt = startedAt;
        }

        private void complete(long generationMillis, long cleanupMillis, boolean failed) {
            this.completed++;
            this.generationMillis += generationMillis;
            this.cleanupMillis += cleanupMillis;
            if (failed) this.failed++;
        }

        private UUID requesterId() { return requesterId; }
        private int total() { return total; }
        private long startedAt() { return startedAt; }
        private int completed() { return completed; }
        private int failed() { return failed; }
        private long averageGenerationMillis() { return completed == 0 ? 0 : generationMillis / completed; }
        private long averageCleanupMillis() { return completed == 0 ? 0 : cleanupMillis / completed; }
    }
}
