package com.nmdungeon.editor;

import com.nmdungeon.NMDungeonPlugin;
import com.nmdungeon.dungeon.ModuleType;
import com.nmdungeon.dungeon.BlockPosition;
import java.io.IOException;
import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.RayTraceResult;
import com.sk89q.worldedit.regions.CuboidRegion;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

public final class DungeonEditSessionManager {
    private final NMDungeonPlugin plugin;
    private final DungeonRepository repository;
    private final FaweSchematicExporter schematicExporter;
    private DungeonValidator validator;
    private final Map<UUID, DungeonEditSession> sessionsByPlayer = new HashMap<>();
    private final Map<String, UUID> editorLocks = new HashMap<>();
    private final Map<UUID, ItemStack> replacedHotbarItems = new HashMap<>();
    private final Map<UUID, DungeonEditorMenu.InputType> pendingTextInputs = new HashMap<>();
    private final Map<UUID, String> pendingTextContexts = new HashMap<>();
    private final Map<UUID, ConnectorDraft> pendingConnectorPlacements = new HashMap<>();
    private final Map<UUID, DoorDraft> pendingDoorDrafts = new HashMap<>();

    public DungeonEditSessionManager(NMDungeonPlugin plugin, DungeonRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.schematicExporter = new FaweSchematicExporter(plugin, repository);
        this.validator = new DungeonValidator(plugin.getConfig());
    }

    public void reloadSettings() {
        this.validator = new DungeonValidator(plugin.getConfig());
    }

    public boolean create(Player player, String dungeonId) {
        if (!isValidId(dungeonId)) {
            send(player, "§c던전 ID는 영문 소문자, 숫자, 밑줄, 하이픈만 사용할 수 있습니다.");
            return false;
        }
        if (repository.exists(dungeonId)) {
            send(player, "§c이미 존재하는 던전 ID입니다.");
            return false;
        }
        try {
            repository.create(dungeonId);
            send(player, "§a던전 §f" + dungeonId + "§a을(를) 만들었습니다. /nmdungeon edit " + dungeonId);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Dungeon create failed: " + exception.getMessage());
            send(player, "§c던전 파일을 만들지 못했습니다.");
            return false;
        }
    }

    /** Deletes saved dungeon data and its persisted editor world. An active edit session is never removed. */
    public DeleteResult deleteDungeon(String dungeonId) {
        if (!isValidId(dungeonId)) return DeleteResult.INVALID_ID;
        if (!repository.exists(dungeonId)) return DeleteResult.NOT_FOUND;
        if (editorLocks.containsKey(dungeonId)) return DeleteResult.EDITING;

        World editorWorld = Bukkit.getWorld("nm_edit_" + dungeonId);
        if (editorWorld != null) {
            if (!editorWorld.getPlayers().isEmpty() || !Bukkit.unloadWorld(editorWorld, true)) {
                return DeleteResult.EDITOR_WORLD_IN_USE;
            }
        }
        try {
            repository.delete(dungeonId);
            deleteEditorWorldFolder(dungeonId);
            return DeleteResult.DELETED;
        } catch (IOException exception) {
            plugin.getLogger().warning("Dungeon deletion failed for " + dungeonId + ": " + exception.getMessage());
            return DeleteResult.FAILED;
        }
    }

    public void open(Player player, String dungeonId) {
        if (sessionsByPlayer.containsKey(player.getUniqueId())) {
            send(player, "§c이미 다른 던전 편집 세션을 열고 있습니다.");
            return;
        }
        UUID lockOwner = editorLocks.get(dungeonId);
        if (lockOwner != null && !lockOwner.equals(player.getUniqueId())) {
            send(player, "§c이 던전은 다른 관리자가 편집 중입니다.");
            return;
        }
        try {
            DungeonDefinition definition = repository.load(dungeonId);
            World world = createEditorWorld(dungeonId, player.getUniqueId());
            DungeonEditSession session = new DungeonEditSession(player.getUniqueId(), definition, world);
            sessionsByPlayer.put(player.getUniqueId(), session);
            editorLocks.put(dungeonId, player.getUniqueId());
            player.teleportAsync(new Location(world, 0.5, 65, 0.5)).thenAccept(success -> {
                if (success) {
                    send(player, "§a편집 세션을 열었습니다: §f" + dungeonId);
                    send(player, "§7평지 작업장입니다. 이 월드는 저장되어 다음 편집 때도 유지됩니다.");
                    giveEditorBook(player);
                    send(player, "§b핫바 9번 슬롯의 [에디터 열기] 책을 우클릭하세요.");
                }
            });
        } catch (IOException exception) {
            send(player, "§c던전 설정을 읽지 못했습니다.");
        }
    }

    public DungeonEditSession session(Player player) {
        return sessionsByPlayer.get(player.getUniqueId());
    }

    public void openDashboard(Player player) {
        DungeonEditSession session = session(player);
        if (session == null) {
            send(player, "§c열린 편집 세션이 없습니다.");
            return;
        }
        DungeonEditorMenu.open(player, session);
    }

    public void openModules(Player player) {
        DungeonEditSession session = session(player);
        if (session != null) DungeonEditorMenu.openModules(player, session);
    }

    public void openRules(Player player) {
        DungeonEditSession session = session(player);
        if (session != null) DungeonEditorMenu.openRules(player, session);
    }

    public void openDungeonRewards(Player player) {
        DungeonEditSession session = session(player);
        if (session != null) DungeonEditorMenu.openDungeonRewards(player, session);
    }

    public void openDungeonRewardChanceInput(Player player, int rewardIndex) {
        DungeonEditSession session = session(player);
        if (session == null || rewardIndex < 0 || rewardIndex >= session.definition().clearRewards().size()) return;
        pendingTextInputs.put(player.getUniqueId(), DungeonEditorMenu.InputType.REWARD_CHANCE);
        pendingTextContexts.put(player.getUniqueId(), String.valueOf(rewardIndex));
        double chance = session.definition().clearRewardChance(rewardIndex);
        String initial = chance == Math.rint(chance) ? String.format(java.util.Locale.ROOT, "%.0f", chance)
                : String.format(java.util.Locale.ROOT, "%.2f", chance);
        DungeonEditorMenu.openTextInput(player, session.definition().id(), DungeonEditorMenu.InputType.REWARD_CHANCE, initial);
    }

    public void openModuleRewardChanceInput(Player player, String moduleId, int rewardIndex) {
        DungeonEditSession session = session(player);
        ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        if (session == null || module == null || rewardIndex < 0 || rewardIndex >= module.clearRewards().size()) return;
        pendingTextInputs.put(player.getUniqueId(), DungeonEditorMenu.InputType.MODULE_REWARD_CHANCE);
        pendingTextContexts.put(player.getUniqueId(), moduleId + "\u0000" + rewardIndex);
        double chance = module.clearRewardChance(rewardIndex);
        String initial = chance == Math.rint(chance) ? String.format(java.util.Locale.ROOT, "%.0f", chance)
                : String.format(java.util.Locale.ROOT, "%.2f", chance);
        DungeonEditorMenu.openTextInput(player, session.definition().id(), DungeonEditorMenu.InputType.MODULE_REWARD_CHANCE, initial);
    }

    public void adjustDungeonRewardChance(Player player, int index, double adjustment) {
        DungeonEditSession session = requireSession(player);
        if (session == null || index < 0 || index >= session.definition().clearRewards().size()) return;
        session.definition().setClearRewardChance(index, session.definition().clearRewardChance(index) + adjustment);
        session.markDirty();
        DungeonEditorMenu.openDungeonRewardChances(player, session, index);
    }

    public void addDungeonRewardFromInventory(Player player, ItemStack item) {
        DungeonEditSession session = requireSession(player);
        if (session == null || item == null || item.getType().isAir()) return;
        if (session.definition().clearRewards().size() >= 18) {
            send(player, "§c던전 보상은 최대 18개까지 등록할 수 있습니다.");
            return;
        }
        session.definition().addClearReward(item);
        session.markDirty();
        openDungeonRewards(player);
    }

    public void openModuleRewards(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        if (session != null && module != null) DungeonEditorMenu.openModuleRewards(player, session, module);
    }

    public void openModuleClearCommands(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        if (session != null && module != null) DungeonEditorMenu.openModuleClearCommands(player, session, module);
    }

    public void openModuleClearCommandInput(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        if (session == null || module == null) return;
        pendingTextInputs.put(player.getUniqueId(), DungeonEditorMenu.InputType.MODULE_CLEAR_COMMAND);
        pendingTextContexts.put(player.getUniqueId(), moduleId);
        DungeonEditorMenu.openTextInput(player, session.definition().id(), DungeonEditorMenu.InputType.MODULE_CLEAR_COMMAND);
    }

    public void removeModuleClearCommand(Player player, String moduleId, int index) {
        DungeonEditSession session = requireSession(player); if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId); if (module == null) return;
        if (module.removeClearCommand(index)) session.markDirty();
        openModuleClearCommands(player, moduleId);
    }

    public void toggleCombatRoomCorridorRule(Player player) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        session.definition().toggleCombatRoomCorridorRule();
        session.markDirty();
        openRules(player);
    }

    public void toggleRequireAllModulesClearedForBoss(Player player) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        session.definition().toggleRequireAllModulesClearedForBoss();
        session.markDirty();
        openRules(player);
    }

    public void openModuleTarget(Player player, String target) {
        DungeonEditSession session = session(player);
        if (session != null) DungeonEditorMenu.openModuleTarget(player, session, target);
    }

    public void openValidation(Player player) {
        DungeonEditSession session = session(player);
        if (session != null) DungeonEditorMenu.openValidation(player, session, validator.validate(session.definition()));
    }

    public void openModuleDetail(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) {
            send(player, "§c등록되지 않은 모듈입니다: " + moduleId);
            openModules(player);
            return;
        }
        DungeonEditorMenu.openModuleDetail(player, session, module);
    }

    public void openConnectors(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) return;
        DungeonEditorMenu.openConnectors(player, session, module);
    }

    public void openConnectorDetail(Player player, String moduleId, String connectorId) {
        DungeonEditSession session = session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) return;
        ConnectorDefinition connector = module.findConnector(connectorId);
        if (connector == null) {
            send(player, "§c연결점을 찾지 못했습니다: " + connectorId);
            openConnectors(player, moduleId);
            return;
        }
        DungeonEditorMenu.openConnectorDetail(player, session, module, connector);
    }

    public void openDoors(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) return;
        DungeonEditorMenu.openDoors(player, session, module);
    }

    public void openCombat(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module != null) DungeonEditorMenu.openCombat(player, session, module);
    }

    public void openSpawnPoints(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module != null) DungeonEditorMenu.openSpawnPoints(player, session, module);
    }

    public void openWaves(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module != null) DungeonEditorMenu.openWaves(player, session, module);
    }

    public void openWaveDetail(Player player, String moduleId, String waveId) {
        DungeonEditSession session = session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        WaveDefinition wave = module == null ? null : module.findWave(waveId);
        if (module != null && wave != null) DungeonEditorMenu.openWaveDetail(player, session, module, wave);
    }

    public void openSpawnIdInput(Player player, String moduleId) {
        openCombatTextInput(player, moduleId, DungeonEditorMenu.InputType.SPAWN_ID);
    }

    public void openWaveIdInput(Player player, String moduleId) {
        openCombatTextInput(player, moduleId, DungeonEditorMenu.InputType.WAVE_ID);
    }

    public void openWaveSpawnPicker(Player player, String moduleId, String waveId) {
        DungeonEditSession session = session(player);
        ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        if (module == null) return;
        if (module.spawnPoints().isEmpty()) {
            send(player, "§c먼저 스폰 지점을 하나 이상 등록하세요.");
            openWaveDetail(player, moduleId, waveId);
            return;
        }
        DungeonEditorMenu.openSpawnPicker(player, session, module, moduleId + "\u0000" + waveId, false);
    }

    public void openBossSpawnPicker(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        if (module == null) return;
        if (module.spawnPoints().isEmpty()) {
            send(player, "§c먼저 스폰 지점을 하나 이상 등록하세요.");
            openCombat(player, moduleId);
            return;
        }
        DungeonEditorMenu.openSpawnPicker(player, session, module, moduleId, true);
    }

    private void openCombatTextInput(Player player, String context, DungeonEditorMenu.InputType inputType) {
        DungeonEditSession session = session(player);
        if (session == null) return;
        pendingTextInputs.put(player.getUniqueId(), inputType);
        pendingTextContexts.put(player.getUniqueId(), context);
        DungeonEditorMenu.openTextInput(player, session.definition().id(), inputType);
    }

    public void adjustModuleWeight(Player player, String moduleId, int adjustment) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        module.setWeight(module.weight() + adjustment);
        session.markDirty();
        DungeonEditorMenu.openModuleDetail(player, session, module);
    }

    public void adjustModuleInstanceLimit(Player player, String moduleId, boolean minimum, int adjustment) {
        DungeonEditSession session = requireSession(player); if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId); if (module == null) return;
        int min = module.minInstances();
        int max = module.maxInstances();
        if (minimum) {
            min = Math.max(0, min + adjustment);
            if (max > 0 && min > max) min = max;
        } else {
            max = Math.max(0, max + adjustment);
            if (max > 0 && max < min) max = min;
        }
        module.setInstanceLimits(min, max);
        session.markDirty();
        openModuleDetail(player, moduleId);
    }

    public void toggleModuleRotation(Player player, String moduleId, int rotation) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (!module.toggleRotation(rotation)) {
            send(player, "§c허용 회전은 최소 하나를 유지해야 합니다.");
            return;
        }
        session.markDirty();
        DungeonEditorMenu.openModuleDetail(player, session, module);
    }

    public void teleportToModuleOrigin(Player player, String moduleId) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (module.templateOrigin() == null) {
            send(player, "§c먼저 FAWE 선택 영역을 export해야 편집 원점이 생성됩니다.");
            return;
        }
        BlockPosition origin = module.templateOrigin();
        player.teleport(new Location(session.world(), origin.x() + 0.5, origin.y() + 1, origin.z() + 0.5));
        send(player, "§a모듈 편집 원점으로 이동했습니다: §f" + moduleId);
    }

    public void openModuleIdInput(Player player) {
        DungeonEditSession session = session(player);
        if (session != null) {
            pendingTextInputs.put(player.getUniqueId(), DungeonEditorMenu.InputType.MODULE_ID);
            pendingTextContexts.remove(player.getUniqueId());
            plugin.getLogger().info("[EditorDebug] Opened module ID Anvil for " + player.getName());
            DungeonEditorMenu.openTextInput(player, session.definition().id(), DungeonEditorMenu.InputType.MODULE_ID);
        }
    }

    public DungeonEditorMenu.InputType pendingTextInput(Player player) {
        return pendingTextInputs.get(player.getUniqueId());
    }

    public void clearPendingTextInput(Player player) {
        pendingTextInputs.remove(player.getUniqueId());
        pendingTextContexts.remove(player.getUniqueId());
    }

    public void openConnectorIdInput(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null || !session.definition().modules().containsKey(moduleId)) return;
        pendingTextInputs.put(player.getUniqueId(), DungeonEditorMenu.InputType.CONNECTOR_ID);
        pendingTextContexts.put(player.getUniqueId(), moduleId);
        DungeonEditorMenu.openTextInput(player, session.definition().id(), DungeonEditorMenu.InputType.CONNECTOR_ID);
    }

    public void openDoorIdInput(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null || !session.definition().modules().containsKey(moduleId)) return;
        pendingTextInputs.put(player.getUniqueId(), DungeonEditorMenu.InputType.DOOR_ID);
        pendingTextContexts.put(player.getUniqueId(), moduleId);
        DungeonEditorMenu.openTextInput(player, session.definition().id(), DungeonEditorMenu.InputType.DOOR_ID);
    }

    public void completeTextInput(Player player, String input) {
        DungeonEditorMenu.InputType type = pendingTextInput(player);
        if (type == DungeonEditorMenu.InputType.MODULE_ID) {
            completeModuleIdInput(player, input);
            return;
        }
        if (type == DungeonEditorMenu.InputType.CONNECTOR_ID) {
            String moduleId = pendingTextContexts.get(player.getUniqueId());
            completeConnectorIdInput(player, moduleId, input);
        }
        if (type == DungeonEditorMenu.InputType.DOOR_ID) {
            String moduleId = pendingTextContexts.get(player.getUniqueId());
            completeDoorIdInput(player, moduleId, input);
        }
        if (type == DungeonEditorMenu.InputType.SPAWN_ID) completeSpawnIdInput(player, pendingTextContexts.get(player.getUniqueId()), input);
        if (type == DungeonEditorMenu.InputType.WAVE_ID) completeWaveIdInput(player, pendingTextContexts.get(player.getUniqueId()), input);
        if (type == DungeonEditorMenu.InputType.WAVE_MOB_ID) completeWaveMobInput(player, pendingTextContexts.get(player.getUniqueId()), input);
        if (type == DungeonEditorMenu.InputType.BOSS_MOB_ID) completeBossMobInput(player, pendingTextContexts.get(player.getUniqueId()), input);
        if (type == DungeonEditorMenu.InputType.REWARD_CHANCE) completeRewardChanceInput(player, pendingTextContexts.get(player.getUniqueId()), input);
        if (type == DungeonEditorMenu.InputType.MODULE_REWARD_CHANCE) completeModuleRewardChanceInput(player, pendingTextContexts.get(player.getUniqueId()), input);
        if (type == DungeonEditorMenu.InputType.MODULE_CLEAR_COMMAND) completeModuleClearCommandInput(player, pendingTextContexts.get(player.getUniqueId()), input);
    }

    private void completeRewardChanceInput(Player player, String context, String input) {
        int rewardIndex;
        try { rewardIndex = Integer.parseInt(context); }
        catch (NumberFormatException exception) { clearPendingTextInput(player); openDungeonRewards(player); return; }
        double chance;
        try { chance = Double.parseDouble(input.trim()); }
        catch (NumberFormatException exception) {
            send(player, "§c확률은 0~100 사이의 숫자로 입력하세요.");
            openDungeonRewardChanceInput(player, rewardIndex);
            return;
        }
        if (chance < 0.0D || chance > 100.0D) {
            send(player, "§c확률은 0~100 사이여야 합니다.");
            openDungeonRewardChanceInput(player, rewardIndex);
            return;
        }
        clearPendingTextInput(player);
        DungeonEditSession session = session(player);
        if (session == null || rewardIndex >= session.definition().clearRewards().size()) { openDungeonRewards(player); return; }
        session.definition().setClearRewardChance(rewardIndex, chance);
        session.markDirty();
        openDungeonRewards(player);
    }

    private void completeModuleRewardChanceInput(Player player, String context, String input) {
        String[] values = context == null ? new String[0] : context.split("\u0000", 2);
        int rewardIndex;
        try { rewardIndex = values.length == 2 ? Integer.parseInt(values[1]) : -1; }
        catch (NumberFormatException exception) { rewardIndex = -1; }
        DungeonEditSession session = session(player);
        ModuleDefinition module = values.length == 2 && session != null ? session.definition().modules().get(values[0]) : null;
        if (module == null || rewardIndex < 0 || rewardIndex >= module.clearRewards().size()) { clearPendingTextInput(player); openDungeonRewards(player); return; }
        double chance;
        try { chance = Double.parseDouble(input.trim()); }
        catch (NumberFormatException exception) {
            send(player, "§c확률은 0~100 사이의 숫자로 입력하세요.");
            openModuleRewardChanceInput(player, values[0], rewardIndex);
            return;
        }
        if (chance < 0.0D || chance > 100.0D) {
            send(player, "§c확률은 0~100 사이여야 합니다.");
            openModuleRewardChanceInput(player, values[0], rewardIndex);
            return;
        }
        clearPendingTextInput(player);
        module.setClearRewardChance(rewardIndex, chance);
        session.markDirty();
        openModuleRewards(player, values[0]);
    }

    private void completeModuleClearCommandInput(Player player, String moduleId, String input) {
        clearPendingTextInput(player);
        DungeonEditSession session = session(player);
        ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        if (module == null) return;
        if (!module.addClearCommand(input)) {
            openModuleClearCommands(player, moduleId);
            return;
        }
        session.markDirty();
        openModuleClearCommands(player, moduleId);
    }

    private void completeSpawnIdInput(Player player, String moduleId, String spawnId) {
        clearPendingTextInput(player);
        if (!isValidModuleId(spawnId)) { send(player, "§c스폰 지점 ID는 한글·영문·숫자·_·-만 1~32자로 입력하세요."); openSpawnPoints(player, moduleId); return; }
        addSpawnPoint(player, moduleId, spawnId);
        openSpawnPoints(player, moduleId);
    }

    private void completeWaveIdInput(Player player, String moduleId, String waveId) {
        clearPendingTextInput(player);
        if (!isValidModuleId(waveId)) { send(player, "§c웨이브 ID는 한글·영문·숫자·_·-만 1~32자로 입력하세요."); openWaves(player, moduleId); return; }
        DungeonEditSession session = session(player);
        ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        if (module == null) return;
        if (module.findWave(waveId) != null) { send(player, "§c이미 존재하는 웨이브 ID입니다."); openWaves(player, moduleId); return; }
        module.findOrCreateWave(waveId);
        session.markDirty();
        openWaveDetail(player, moduleId, waveId);
    }

    public void selectWaveSpawnPoint(Player player, String moduleId, String waveId, String spawnId) {
        openCombatTextInput(player, moduleId + "\u0000" + waveId + "\u0000" + spawnId, DungeonEditorMenu.InputType.WAVE_MOB_ID);
    }

    private void completeWaveMobInput(Player player, String context, String mobId) {
        clearPendingTextInput(player);
        String[] values = context == null ? new String[0] : context.split("\u0000", 3);
        if (values.length != 3 || mobId.isBlank()) { send(player, "§cMythicMob ID를 입력하세요."); return; }
        addWaveSpawn(player, values[0], values[1], values[2], mobId.trim(), 1, 1.0D);
        openWaveDetail(player, values[0], values[1]);
    }

    public void selectBossSpawnPoint(Player player, String moduleId, String spawnId) {
        openCombatTextInput(player, moduleId + "\u0000" + spawnId, DungeonEditorMenu.InputType.BOSS_MOB_ID);
    }

    private void completeBossMobInput(Player player, String context, String mobId) {
        clearPendingTextInput(player);
        String[] values = context == null ? new String[0] : context.split("\u0000", 2);
        if (values.length != 2 || mobId.isBlank()) { send(player, "§cMythicMob ID를 입력하세요."); return; }
        setBoss(player, values[0], values[1], mobId.trim(), 1.0D);
        openCombat(player, values[0]);
    }

    public void deleteSpawnPoint(Player player, String moduleId, String spawnId) {
        DungeonEditSession session = requireSession(player); if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId); if (module == null) return;
        if (module.waves().stream().flatMap(w -> w.spawns().stream()).anyMatch(s -> s.spawnPointId().equalsIgnoreCase(spawnId))
                || (module.boss() != null && module.boss().spawnPointId().equalsIgnoreCase(spawnId))) {
            send(player, "§c웨이브 또는 보스가 사용하는 스폰 지점은 삭제할 수 없습니다."); return;
        }
        if (module.removeSpawnPoint(spawnId)) session.markDirty();
        openSpawnPoints(player, moduleId);
    }

    public void teleportToSpawnPoint(Player player, String moduleId, String spawnId) {
        DungeonEditSession session = session(player); ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        SpawnPointDefinition point = module == null ? null : module.findSpawnPoint(spawnId);
        if (session != null && module != null && point != null && module.templateOrigin() != null) {
            BlockPosition origin = module.templateOrigin(); BlockPosition local = point.position();
            player.teleport(new Location(session.world(), origin.x() + local.x() + 0.5D, origin.y() + local.y() + 1, origin.z() + local.z() + 0.5D));
        }
    }

    public void deleteWave(Player player, String moduleId, String waveId) {
        DungeonEditSession session = requireSession(player); if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId); if (module == null) return;
        if (module.removeWave(waveId)) session.markDirty();
        openWaves(player, moduleId);
    }

    public void adjustWaveSpawn(Player player, String moduleId, String waveId, int index, int amountDelta, double levelDelta) {
        DungeonEditSession session = requireSession(player); if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId); WaveDefinition wave = module == null ? null : module.findWave(waveId);
        if (wave != null && wave.adjustSpawn(index, amountDelta, levelDelta)) session.markDirty();
        openWaveDetail(player, moduleId, waveId);
    }

    public void deleteWaveSpawn(Player player, String moduleId, String waveId, int index) {
        DungeonEditSession session = requireSession(player); if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId); WaveDefinition wave = module == null ? null : module.findWave(waveId);
        if (wave != null && wave.removeSpawn(index)) session.markDirty();
        openWaveDetail(player, moduleId, waveId);
    }

    public void adjustBossLevel(Player player, String moduleId, double adjustment) {
        DungeonEditSession session = requireSession(player); if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId); if (module == null || module.boss() == null) return;
        BossDefinition boss = module.boss();
        module.setBoss(new BossDefinition(boss.spawnPointId(), boss.mythicMobId(), Math.max(0.1D, boss.level() + adjustment)));
        session.markDirty();
        openCombat(player, moduleId);
    }

    public void completeModuleIdInput(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null) {
            plugin.getLogger().warning("[EditorDebug] Module ID submit without an edit session: " + player.getName());
            return;
        }
        plugin.getLogger().info("[EditorDebug] Module ID submitted by " + player.getName() + ": '" + moduleId + "'");
        if (!isValidModuleId(moduleId)) {
            send(player, "§c모듈 ID '" + moduleId + "'는 사용할 수 없습니다. 한글·영문·숫자·_·-만 1~32자로 입력하세요.");
            return;
        }
        if (session.definition().modules().containsKey(moduleId)) {
            send(player, "§c이미 존재하는 모듈 ID입니다.");
            return;
        }
        clearPendingTextInput(player);
        DungeonEditorMenu.openModuleTypePicker(player, session.definition().id(), moduleId);
    }

    private void completeConnectorIdInput(Player player, String moduleId, String connectorId) {
        DungeonEditSession session = session(player);
        if (session == null || moduleId == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) return;
        if (!isValidModuleId(connectorId)) {
            send(player, "§c연결점 ID는 한글·영문·숫자·_·-만 1~32자로 입력하세요.");
            return;
        }
        if (module.findConnector(connectorId) != null) {
            send(player, "§c이미 존재하는 연결점 ID입니다.");
            return;
        }
        if (module.templateOrigin() == null) {
            send(player, "§c먼저 이 모듈을 FAWE로 export해야 연결점을 지정할 수 있습니다.");
            clearPendingTextInput(player);
            openModuleDetail(player, moduleId);
            return;
        }
        clearPendingTextInput(player);
        pendingConnectorPlacements.put(player.getUniqueId(), new ConnectorDraft(moduleId, connectorId, 3, 3));
        player.closeInventory();
        send(player, "§e연결점 위치 지정: §f" + connectorId + " §7(기본 3×3)");
        send(player, "§7모듈의 외벽 블록을 바라보고 §f우클릭§7하세요. 취소: 다시 에디터 책 우클릭");
    }

    private void completeDoorIdInput(Player player, String moduleId, String doorId) {
        DungeonEditSession session = session(player);
        if (session == null || moduleId == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) return;
        if (!isValidModuleId(doorId)) {
            send(player, "§c문 ID는 한글·영문·숫자·_·-만 1~32자로 입력하세요.");
            return;
        }
        if (module.doors().stream().anyMatch(door -> door.id().equalsIgnoreCase(doorId))) {
            send(player, "§c이미 존재하는 문 ID입니다.");
            return;
        }
        if (module.templateOrigin() == null) {
            send(player, "§c먼저 이 모듈을 FAWE로 export해야 문을 지정할 수 있습니다.");
            clearPendingTextInput(player);
            openModuleDetail(player, moduleId);
            return;
        }
        if (module.connectors().isEmpty()) {
            send(player, "§c먼저 연결점을 하나 이상 등록하세요.");
            clearPendingTextInput(player);
            openDoors(player, moduleId);
            return;
        }
        clearPendingTextInput(player);
        pendingDoorDrafts.put(player.getUniqueId(), new DoorDraft(moduleId, doorId, null));
        DungeonEditorMenu.openDoorConnectorPicker(player, session, module, doorId);
    }

    public void chooseDoorConnector(Player player, String moduleId, String doorId, String connectorId) {
        DungeonEditSession session = session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null || module.findConnector(connectorId) == null) return;
        pendingDoorDrafts.put(player.getUniqueId(), new DoorDraft(moduleId, doorId, connectorId));
        player.closeInventory();
        send(player, "§e문 영역 선택: §f" + doorId + " §7→ " + connectorId);
        send(player, "§7FAWE 완드로 문 블록의 두 모서리를 선택한 뒤, §f에디터 책을 우클릭§7하세요.");
    }

    public void resumeEditorBook(Player player) {
        DoorDraft draft = pendingDoorDrafts.get(player.getUniqueId());
        if (draft != null && draft.connectorId() != null) {
            DungeonEditSession session = session(player);
            ModuleDefinition module = session == null ? null : session.definition().modules().get(draft.moduleId());
            if (module != null) {
                DungeonEditorMenu.openDoorSelectionApply(player, session, module, draft.doorId(), draft.connectorId());
                return;
            }
            pendingDoorDrafts.remove(player.getUniqueId());
        }
        openDashboard(player);
    }

    public void applyPendingDoor(Player player) {
        DoorDraft draft = pendingDoorDrafts.get(player.getUniqueId());
        if (draft == null || draft.connectorId() == null) {
            send(player, "§c적용할 문 초안이 없습니다.");
            return;
        }
        if (setDoor(player, draft.moduleId(), draft.doorId(), draft.connectorId(), "CLEAR_ROOM")) {
            pendingDoorDrafts.remove(player.getUniqueId());
            openDoors(player, draft.moduleId());
        }
    }

    public void cancelPendingDoor(Player player, String moduleId) {
        pendingDoorDrafts.remove(player.getUniqueId());
        openDoors(player, moduleId);
    }

    public boolean hasPendingConnectorPlacement(Player player) {
        return pendingConnectorPlacements.containsKey(player.getUniqueId());
    }

    public void placePendingConnector(Player player, Block block, BlockFace face) {
        ConnectorDraft draft = pendingConnectorPlacements.get(player.getUniqueId());
        if (draft == null) return;
        if (registerConnector(player, draft.moduleId(), draft.connectorId(), draft.width(), draft.height(), block, face)) {
            pendingConnectorPlacements.remove(player.getUniqueId());
            openConnectors(player, draft.moduleId());
        }
    }

    public void createModuleFromGui(Player player, String moduleId, ModuleType type) {
        addModule(player, moduleId, type);
    }

    public void giveEditorBook(Player player) {
        if (session(player) == null) return;
        int slot = 8;
        if (!replacedHotbarItems.containsKey(player.getUniqueId())) {
            ItemStack original = player.getInventory().getItem(slot);
            replacedHotbarItems.put(player.getUniqueId(), original == null ? null : original.clone());
        }
        player.getInventory().setItem(slot, EditorBook.create(plugin));
    }

    public void addModule(Player player, String moduleId, ModuleType type) {
        DungeonEditSession session = session(player);
        if (session == null) {
            send(player, "§c먼저 /nmdungeon edit <dungeonId>로 편집 세션을 여세요.");
            return;
        }
        if (!isValidModuleId(moduleId)) {
            send(player, "§c모듈 ID는 한글·영문·숫자·밑줄·하이픈만 사용할 수 있습니다.");
            return;
        }
        if (session.definition().modules().containsKey(moduleId)) {
            send(player, "§c이미 존재하는 모듈 ID입니다.");
            return;
        }
        session.definition().addModule(ModuleDefinition.createDefault(moduleId, type));
        session.markDirty();
        send(player, "§a모듈을 추가했습니다: §f" + moduleId + " §7(" + type + ")");
        DungeonEditorMenu.openModules(player, session);
    }

    public void exportModule(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null) {
            send(player, "§c먼저 /nmdungeon edit <dungeonId>로 편집 세션을 여세요.");
            return;
        }
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) {
            send(player, "§c등록되지 않은 모듈입니다: " + moduleId);
            return;
        }
        schematicExporter.export(player, session, module);
    }

    public void addConnector(Player player, String moduleId, String connectorId, int width, int height) {
        RayTraceResult trace = player.rayTraceBlocks(8);
        if (trace == null || trace.getHitBlock() == null || trace.getHitBlockFace() == null) {
            send(player, "§c8블록 이내의 모듈 외벽 블록 가운데를 바라보고 다시 실행하세요.");
            return;
        }
        registerConnector(player, moduleId, connectorId, width, height, trace.getHitBlock(), trace.getHitBlockFace());
    }

    private boolean registerConnector(Player player, String moduleId, String connectorId, int width, int height, Block hitBlock, BlockFace hitFace) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return false;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return false;
        if (module.templateOrigin() == null) {
            send(player, "§c먼저 이 모듈을 FAWE로 export해야 연결점 기준 좌표를 지정할 수 있습니다.");
            return false;
        }
        if (width < 1 || height < 1 || width > 15 || height > 15) {
            send(player, "§c연결점 폭·높이는 1~15 블록이어야 합니다.");
            return false;
        }
        if (!hitBlock.getWorld().equals(session.world())) {
            send(player, "§c현재 편집 월드에서만 연결점을 지정할 수 있습니다.");
            return false;
        }
        String facing = facing(hitFace);
        if (facing == null) {
            send(player, "§c위·아래 면에는 연결점을 만들 수 없습니다.");
            return false;
        }
        BlockPosition origin = module.templateOrigin();
        BlockPosition local = new BlockPosition(hitBlock.getX() - origin.x(), hitBlock.getY() - origin.y(), hitBlock.getZ() - origin.z());
        if (!isInside(module, local) || !isOnFacingBoundary(module, local, facing)) {
            send(player, "§c선택한 블록은 이 모듈의 " + facing + " 외벽이 아닙니다. 모듈 export 선택 영역의 외벽을 바라보세요.");
            return false;
        }
        if (!fitsConnectorOpening(module, local, facing, width, height)) {
            send(player, "§c연결점 " + width + "×" + height + " 크기가 모듈 외벽 범위를 벗어납니다. 외벽의 더 안쪽 블록을 선택하거나 크기를 줄이세요.");
            return false;
        }
        if (!module.addConnector(new ConnectorDefinition(connectorId, facing, local.x(), local.y(), local.z(), width, height, true, List.of("main", "branch")))) {
            send(player, "§c이미 같은 ID의 연결점이 있습니다.");
            return false;
        }
        session.markDirty();
        send(player, "§a연결점 등록: §f" + connectorId + " §7(" + facing + ", " + width + "×" + height + ")");
        send(player, "§7문을 지정하려면 FAWE로 문 영역을 선택한 뒤 /nmdungeon door set " + moduleId + " <doorId> " + connectorId + " CLEAR_ROOM");
        return true;
    }

    public void changeConnectorSize(Player player, String moduleId, String connectorId, int widthChange, int heightChange) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        ConnectorDefinition connector = module.findConnector(connectorId);
        if (connector == null) return;
        int width = connector.width() + widthChange;
        int height = connector.height() + heightChange;
        if (width < 1 || height < 1 || width > 15 || height > 15) {
            send(player, "§c연결점 폭·높이는 1~15 블록이어야 합니다.");
            return;
        }
        if (!fitsConnectorOpening(module, new BlockPosition(connector.x(), connector.y(), connector.z()), connector.facing(), width, height)) {
            send(player, "§c변경한 크기가 모듈 외벽 범위를 벗어납니다.");
            return;
        }
        module.resizeConnector(connectorId, width, height);
        session.markDirty();
        openConnectorDetail(player, moduleId, connectorId);
    }

    public void toggleConnectorBranch(Player player, String moduleId, String connectorId) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null || !module.toggleConnectorBranch(connectorId)) return;
        session.markDirty();
        openConnectorDetail(player, moduleId, connectorId);
    }

    public void setConnectorPlayerEntry(Player player, String moduleId, String connectorId) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null || module.findConnector(connectorId) == null) return;
        PlayerPositionDefinition point = currentModulePosition(player, module);
        if (point == null) return;
        module.setConnectorEntryPoint(connectorId, point);
        session.markDirty();
        send(player, "§a연결점 진입 지점 저장: §f" + connectorId + " §7(" + point.x() + ", " + point.y() + ", " + point.z() + ")");
        openConnectorDetail(player, moduleId, connectorId);
    }

    public void clearConnectorPlayerEntry(Player player, String moduleId, String connectorId) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null || !module.setConnectorEntryPoint(connectorId, null)) return;
        session.markDirty();
        send(player, "§e연결점 진입 지점을 초기화했습니다: " + connectorId);
        openConnectorDetail(player, moduleId, connectorId);
    }

    public void teleportToConnectorPlayerEntry(Player player, String moduleId, String connectorId) {
        DungeonEditSession session = session(player);
        ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        ConnectorDefinition connector = module == null ? null : module.findConnector(connectorId);
        if (session == null || module == null || connector == null || connector.playerEntryPoint() == null || module.templateOrigin() == null) {
            send(player, "§c저장된 진입 지점이 없습니다.");
            return;
        }
        teleportToLocalPlayerPoint(player, session, module.templateOrigin(), connector.playerEntryPoint());
    }

    public void setModulePlayerPoint(Player player, String moduleId, boolean startPoint) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (startPoint && module.type() != ModuleType.ENTRANCE) {
            send(player, "§c시작 지점은 입구(ENTRANCE) 모듈에만 설정할 수 있습니다.");
            return;
        }
        PlayerPositionDefinition point = currentModulePosition(player, module);
        if (point == null) return;
        if (startPoint) module.setStartPoint(point); else module.setCheckpointPoint(point);
        session.markDirty();
        send(player, "§a" + (startPoint ? "시작 지점" : "체크포인트") + "을 저장했습니다.");
        openModuleDetail(player, moduleId);
    }

    public void clearModulePlayerPoint(Player player, String moduleId, boolean startPoint) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (startPoint) module.setStartPoint(null); else module.setCheckpointPoint(null);
        session.markDirty();
        send(player, "§e" + (startPoint ? "시작 지점" : "체크포인트") + "을 초기화했습니다.");
        openModuleDetail(player, moduleId);
    }

    public void deleteConnectorFromGui(Player player, String moduleId, String connectorId) {
        removeConnector(player, moduleId, connectorId);
        openConnectors(player, moduleId);
    }

    public void removeConnector(Player player, String moduleId, String connectorId) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (module.doors().stream().anyMatch(door -> door.connectorId().equalsIgnoreCase(connectorId))) {
            send(player, "§c이 연결점에 연결된 문이 있습니다. 문을 먼저 삭제하세요.");
            return;
        }
        if (!module.removeConnector(connectorId)) {
            send(player, "§c연결점을 찾지 못했습니다.");
            return;
        }
        session.markDirty();
        send(player, "§e연결점을 삭제했습니다: " + connectorId);
    }

    public void resizeConnector(Player player, String moduleId, String connectorId, int width, int height) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (width < 1 || height < 1 || width > 15 || height > 15) {
            send(player, "§c연결점 폭·높이는 1~15 블록이어야 합니다.");
            return;
        }
        if (!module.resizeConnector(connectorId, width, height)) {
            send(player, "§c연결점을 찾지 못했습니다: " + connectorId);
            return;
        }
        session.markDirty();
        send(player, "§a연결점 크기 변경: §f" + connectorId + " §7→ " + width + "×" + height);
        if (module.doors().stream().anyMatch(door -> door.connectorId().equalsIgnoreCase(connectorId))) {
            send(player, "§e이 연결점에 문이 등록되어 있습니다. 문 선택 영역이 새 연결점 범위와 맞는지 확인하세요.");
        }
    }

    public boolean setDoor(Player player, String moduleId, String doorId, String connectorId, String condition) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return false;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return false;
        if (module.templateOrigin() == null) {
            send(player, "§c먼저 이 모듈을 FAWE로 export하세요.");
            return false;
        }
        ConnectorDefinition connector = module.findConnector(connectorId);
        if (connector == null) {
            send(player, "§c먼저 연결점을 등록하세요: " + connectorId);
            return false;
        }
        CuboidRegion selection = getFaweSelection(player);
        if (selection == null) return false;
        BlockPosition origin = module.templateOrigin();
        BlockPosition pos1 = local(origin, selection.getMinimumPoint());
        BlockPosition pos2 = local(origin, selection.getMaximumPoint());
        if (!isInside(module, pos1) || !isInside(module, pos2) || !isDoorWithinConnectorOpening(pos1, pos2, connector)) {
            send(player, "§c문 선택 영역은 해당 연결점(" + connectorId + ")의 외벽 및 폭·높이 범위 안에 있어야 합니다.");
            return false;
        }
        if (!module.addDoor(new DoorDefinition(doorId, connectorId, condition.toUpperCase(), pos1, pos2))) {
            send(player, "§c이미 같은 ID의 문이 있습니다.");
            return false;
        }
        session.markDirty();
        send(player, "§a문 등록: §f" + doorId + " §7→ " + connectorId + " / 조건: " + condition.toUpperCase());
        return true;
    }

    public void removeDoor(Player player, String moduleId, String doorId) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (!module.removeDoor(doorId)) {
            send(player, "§c문을 찾지 못했습니다.");
            return;
        }
        session.markDirty();
        send(player, "§e문을 삭제했습니다: " + doorId);
    }

    public void save(Player player) {
        DungeonEditSession session = session(player);
        if (session == null) {
            send(player, "§c열린 편집 세션이 없습니다.");
            return;
        }
        try {
            repository.save(session.definition());
            session.markSaved();
            send(player, "§a던전 설정을 저장했습니다.");
        } catch (IOException exception) {
            plugin.getLogger().warning("Dungeon save failed: " + exception.getMessage());
            send(player, "§c던전 설정을 저장하지 못했습니다.");
        }
    }

    public void discard(Player player) {
        DungeonEditSession session = session(player);
        if (session == null) {
            send(player, "§c열린 편집 세션이 없습니다.");
            return;
        }
        try {
            DungeonDefinition reloaded = repository.load(session.definition().id());
            sessionsByPlayer.put(player.getUniqueId(), new DungeonEditSession(player.getUniqueId(), reloaded, session.world()));
            send(player, "§e저장하지 않은 변경 사항을 폐기했습니다.");
            DungeonEditorMenu.open(player, sessionsByPlayer.get(player.getUniqueId()));
        } catch (IOException exception) {
            send(player, "§c저장된 던전 설정을 다시 읽지 못했습니다.");
        }
    }

    public void exit(Player player) {
        DungeonEditSession session = sessionsByPlayer.remove(player.getUniqueId());
        if (session == null) {
            send(player, "§c열린 편집 세션이 없습니다.");
            return;
        }
        editorLocks.remove(session.definition().id());
        clearPendingTextInput(player);
        pendingConnectorPlacements.remove(player.getUniqueId());
        pendingDoorDrafts.remove(player.getUniqueId());
        restoreHotbarItem(player);
        removeEditorBooks(player);
        player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
        Bukkit.unloadWorld(session.world(), true);
        send(player, session.dirty() ? "§e저장하지 않은 변경 사항을 남긴 채 편집을 종료했습니다." : "§a편집 세션을 종료했습니다.");
    }

    public void closeOnDisconnect(Player player) {
        DungeonEditSession session = sessionsByPlayer.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        editorLocks.remove(session.definition().id());
        clearPendingTextInput(player);
        pendingConnectorPlacements.remove(player.getUniqueId());
        pendingDoorDrafts.remove(player.getUniqueId());
        restoreHotbarItem(player);
        removeEditorBooks(player);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.unloadWorld(session.world(), true));
    }

    public List<String> validate(Player player, String requestedDungeonId) {
        DungeonEditSession session = session(player);
        DungeonDefinition definition;
        try {
            definition = session != null && (requestedDungeonId == null || requestedDungeonId.equals(session.definition().id()))
                    ? session.definition() : repository.load(requestedDungeonId);
        } catch (IOException exception) {
            return List.of("던전 설정을 읽지 못했습니다.");
        }
        return validator.validate(definition);
    }

    public List<String> moduleIds(Player player) {
        DungeonEditSession session = session(player);
        return session == null ? List.of() : session.definition().modules().keySet().stream().sorted().toList();
    }

    public List<String> dungeonIds() {
        return repository.dungeonIds();
    }

    public List<String> connectorIds(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null) return List.of();
        ModuleDefinition module = session.definition().modules().get(moduleId);
        return module == null ? List.of() : module.connectors().stream().map(ConnectorDefinition::id).sorted().toList();
    }

    public List<String> doorIds(Player player, String moduleId) {
        DungeonEditSession session = session(player);
        if (session == null) return List.of();
        ModuleDefinition module = session.definition().modules().get(moduleId);
        return module == null ? List.of() : module.doors().stream().map(DoorDefinition::id).sorted().toList();
    }

    public void listModules(Player player) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        if (session.definition().modules().isEmpty()) {
            send(player, "§7등록된 모듈이 없습니다.");
            return;
        }
        send(player, "§b[NMDungeon] 모듈 목록 §7(" + session.definition().modules().size() + ")");
        for (ModuleDefinition module : session.definition().modules().values()) {
            send(player, "§f- " + module.id() + " §7[" + module.type() + "] " + module.sizeX() + "×" + module.sizeY() + "×" + module.sizeZ()
                    + " | 연결점 " + module.connectors().size() + " | 문 " + module.doors().size());
        }
    }

    public void listConnectors(Player player, String moduleId) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (module.connectors().isEmpty()) {
            send(player, "§7" + moduleId + " 모듈에는 등록된 연결점이 없습니다.");
            return;
        }
        send(player, "§b[NMDungeon] " + moduleId + " 연결점 목록");
        for (ConnectorDefinition connector : module.connectors()) {
            send(player, "§f- " + connector.id() + " §7" + connector.facing() + " / " + connector.width() + "×" + connector.height()
                    + " / (" + connector.x() + ", " + connector.y() + ", " + connector.z() + ")"
                    + (connector.allowBranch() ? " / branch" : ""));
        }
    }

    public void listDoors(Player player, String moduleId) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (module.doors().isEmpty()) {
            send(player, "§7" + moduleId + " 모듈에는 등록된 문이 없습니다.");
            return;
        }
        send(player, "§b[NMDungeon] " + moduleId + " 문 목록");
        for (DoorDefinition door : module.doors()) {
            send(player, "§f- " + door.id() + " §7→ " + door.connectorId() + " / " + door.conditionType()
                    + " / (" + door.pos1().x() + "," + door.pos1().y() + "," + door.pos1().z() + ") ~ ("
                    + door.pos2().x() + "," + door.pos2().y() + "," + door.pos2().z() + ")");
        }
    }

    public void listGenerationRules(Player player) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        DungeonDefinition dungeon = session.definition();
        send(player, "§b[NMDungeon] 생성 규칙");
        send(player, "§f길: §e" + dungeon.minCorridors() + " ~ " + dungeon.maxCorridors()
                + " §7| 방: §e" + dungeon.minRooms() + " ~ " + dungeon.maxRooms()
                + " §7| 최대 분기: §e" + dungeon.maxBranches());
        send(player, "§f공유 목숨: §e" + (dungeon.maxLives() == 0 ? "무제한" : dungeon.maxLives())
                + " §7| 제한 시간: §e" + (dungeon.timeLimitSeconds() == 0 ? "무제한" : dungeon.timeLimitSeconds() + "초"));
    }

    public void setGenerationRule(Player player, String key, int value) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        if (value < 0) {
            send(player, "§c생성 규칙 값은 0 이상의 정수여야 합니다.");
            return;
        }
        if (!session.definition().setGenerationRule(key, value)) {
            send(player, "§c규칙 이름: min-corridors, max-corridors, min-rooms, max-rooms, max-branches, max-lives, time-limit-seconds");
            return;
        }
        session.markDirty();
        send(player, "§a생성 규칙 변경: §f" + key + " §7→ §e" + value);
        listGenerationRules(player);
    }

    public void addSpawnPoint(Player player, String moduleId, String spawnPointId) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null || module.templateOrigin() == null) {
            if (module != null) send(player, "§c먼저 모듈을 export한 뒤 스폰 지점을 지정하세요.");
            return;
        }
        BlockPosition origin = module.templateOrigin();
        Location location = player.getLocation();
        BlockPosition local = new BlockPosition(location.getBlockX() - origin.x(), location.getBlockY() - origin.y(), location.getBlockZ() - origin.z());
        if (!isInside(module, local)) {
            send(player, "§c스폰 지점은 export한 모듈 영역 내부에 있어야 합니다.");
            return;
        }
        if (!module.addSpawnPoint(new SpawnPointDefinition(spawnPointId, local))) {
            send(player, "§c이미 존재하는 스폰 지점 ID입니다: " + spawnPointId);
            return;
        }
        session.markDirty();
        send(player, "§a스폰 지점 등록: §f" + spawnPointId + " §7(" + local.x() + ", " + local.y() + ", " + local.z() + ")");
    }

    public void addDungeonRewardFromHand(Player player) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) { send(player, "§c주 손에 보상으로 등록할 아이템을 들어주세요."); return; }
        session.definition().addClearReward(item);
        session.markDirty();
        send(player, "§a던전 완료 보상에 아이템을 추가했습니다.");
        openDungeonRewards(player);
    }

    public void removeDungeonReward(Player player, int index) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        if (session.definition().removeClearReward(index)) session.markDirty();
        openDungeonRewards(player);
    }

    public void addModuleRewardFromHand(Player player, String moduleId) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) { send(player, "§c주 손에 보상으로 등록할 아이템을 들어주세요."); return; }
        module.addClearReward(item);
        session.markDirty();
        send(player, "§a모듈 클리어 보상에 아이템을 추가했습니다.");
        openModuleRewards(player, moduleId);
    }

    public void addModuleRewardFromInventory(Player player, String moduleId, ItemStack item) {
        DungeonEditSession session = requireSession(player);
        ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        if (module == null || item == null || item.getType().isAir()) return;
        if (module.clearRewards().size() >= 18) {
            send(player, "§c모듈 보상은 최대 18개까지 등록할 수 있습니다.");
            return;
        }
        module.addClearReward(item);
        session.markDirty();
        openModuleRewards(player, moduleId);
    }

    public void removeModuleReward(Player player, String moduleId, int index) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (module.removeClearReward(index)) session.markDirty();
        openModuleRewards(player, moduleId);
    }

    private PlayerPositionDefinition currentModulePosition(Player player, ModuleDefinition module) {
        if (module.templateOrigin() == null) {
            send(player, "§c먼저 모듈을 export한 뒤 플레이어 지점을 지정하세요.");
            return null;
        }
        BlockPosition origin = module.templateOrigin();
        Location location = player.getLocation();
        BlockPosition local = new BlockPosition(location.getBlockX() - origin.x(), location.getBlockY() - origin.y(), location.getBlockZ() - origin.z());
        if (!isInside(module, local)) {
            send(player, "§c플레이어 지점은 export한 모듈 영역 내부에 있어야 합니다.");
            return null;
        }
        return new PlayerPositionDefinition(local.x(), local.y(), local.z(), location.getYaw(), location.getPitch());
    }

    private void teleportToLocalPlayerPoint(Player player, DungeonEditSession session, BlockPosition origin,
                                             PlayerPositionDefinition point) {
        player.teleport(new Location(session.world(), origin.x() + point.x() + 0.5D, origin.y() + point.y(),
                origin.z() + point.z() + 0.5D, point.yaw(), point.pitch()));
    }

    public void addWaveSpawn(Player player, String moduleId, String waveId, String spawnPointId, String mythicMobId, int amount, double level) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (module.findSpawnPoint(spawnPointId) == null) {
            send(player, "§c등록된 스폰 지점이 아닙니다: " + spawnPointId);
            return;
        }
        if (amount < 1 || amount > 100 || level <= 0) {
            send(player, "§c수량은 1~100, 레벨은 0보다 커야 합니다.");
            return;
        }
        module.findOrCreateWave(waveId).addSpawn(new WaveSpawnDefinition(spawnPointId, mythicMobId, amount, level));
        session.markDirty();
        send(player, "§a웨이브 등록: §f" + waveId + " §7→ " + mythicMobId + " ×" + amount + " @ " + spawnPointId);
    }

    public void setBoss(Player player, String moduleId, String spawnPointId, String mythicMobId, double level) {
        DungeonEditSession session = requireSession(player);
        if (session == null) return;
        ModuleDefinition module = requireModule(player, session, moduleId);
        if (module == null) return;
        if (module.findSpawnPoint(spawnPointId) == null || level <= 0) {
            send(player, "§c스폰 지점 또는 보스 레벨이 올바르지 않습니다.");
            return;
        }
        module.setBoss(new BossDefinition(spawnPointId, mythicMobId, level));
        session.markDirty();
        send(player, "§a보스 등록: §f" + mythicMobId + " §7@ " + spawnPointId);
    }

    private void restoreHotbarItem(Player player) {
        if (!replacedHotbarItems.containsKey(player.getUniqueId())) {
            return;
        }
        ItemStack original = replacedHotbarItems.remove(player.getUniqueId());
        ItemStack current = player.getInventory().getItem(8);
        if (EditorBook.isEditorBook(plugin, current)) {
            player.getInventory().setItem(8, original);
        } else if (original != null) {
            player.getInventory().addItem(original);
        }
    }

    /** Editor books are temporary session tools and must never remain after leaving the editor. */
    private void removeEditorBooks(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (EditorBook.isEditorBook(plugin, player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
        if (EditorBook.isEditorBook(plugin, player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    private World createEditorWorld(String dungeonId, UUID editorId) {
        String name = "nm_edit_" + dungeonId;
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            return existing;
        }
        World world = Bukkit.createWorld(new WorldCreator(name).generator(new FlatEditorChunkGenerator()));
        if (world == null) {
            throw new IllegalStateException("편집 월드를 만들 수 없습니다.");
        }
        world.setAutoSave(true);
        return world;
    }

    private void deleteEditorWorldFolder(String dungeonId) throws IOException {
        File folder = new File(Bukkit.getWorldContainer(), "nm_edit_" + dungeonId);
        if (!folder.isDirectory()) return;
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
    }

    private boolean isValidId(String id) {
        return id.matches("[a-z0-9_-]{3,32}");
    }

    private boolean isValidModuleId(String id) {
        return id != null && id.matches("[\\p{L}\\p{N}_-]{1,32}");
    }

    private DungeonEditSession requireSession(Player player) {
        DungeonEditSession session = session(player);
        if (session == null) send(player, "§c먼저 /nmdungeon edit <dungeonId>로 편집 세션을 여세요.");
        return session;
    }

    private ModuleDefinition requireModule(Player player, DungeonEditSession session, String moduleId) {
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) send(player, "§c등록되지 않은 모듈입니다: " + moduleId);
        return module;
    }

    private CuboidRegion getFaweSelection(Player player) {
        Plugin installedPlugin = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        if (!(installedPlugin instanceof com.sk89q.worldedit.bukkit.WorldEditPlugin worldEditPlugin)) {
            send(player, "§cFAWE가 활성화되어 있지 않습니다.");
            return null;
        }
        try {
            var selection = worldEditPlugin.getSession(player).getSelection(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player.getWorld()));
            return new CuboidRegion(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player.getWorld()), selection.getMinimumPoint(), selection.getMaximumPoint());
        } catch (com.sk89q.worldedit.IncompleteRegionException exception) {
            send(player, "§cFAWE 완드로 문 영역의 두 모서리를 먼저 선택하세요.");
            return null;
        }
    }

    private BlockPosition local(BlockPosition origin, com.sk89q.worldedit.math.BlockVector3 position) {
        return new BlockPosition(position.x() - origin.x(), position.y() - origin.y(), position.z() - origin.z());
    }

    private boolean isInside(ModuleDefinition module, BlockPosition point) {
        return point.x() >= 0 && point.x() < module.sizeX() && point.y() >= 0 && point.y() < module.sizeY() && point.z() >= 0 && point.z() < module.sizeZ();
    }

    private boolean isOnFacingBoundary(ModuleDefinition module, BlockPosition point, String facing) {
        return switch (facing) {
            case "NORTH" -> point.z() == 0;
            case "SOUTH" -> point.z() == module.sizeZ() - 1;
            case "WEST" -> point.x() == 0;
            case "EAST" -> point.x() == module.sizeX() - 1;
            default -> false;
        };
    }

    private boolean isDoorWithinConnectorOpening(BlockPosition pos1, BlockPosition pos2, ConnectorDefinition connector) {
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

    private boolean fitsConnectorOpening(ModuleDefinition module, BlockPosition center, String facing, int width, int height) {
        int minY = center.y() - height / 2;
        int maxY = minY + height - 1;
        if (minY < 0 || maxY >= module.sizeY()) return false;
        int minHorizontal = -(width / 2);
        int maxHorizontal = minHorizontal + width - 1;
        return switch (facing) {
            case "NORTH", "SOUTH" -> center.x() + minHorizontal >= 0 && center.x() + maxHorizontal < module.sizeX();
            case "WEST", "EAST" -> center.z() + minHorizontal >= 0 && center.z() + maxHorizontal < module.sizeZ();
            default -> false;
        };
    }

    private String facing(BlockFace face) {
        return switch (face) {
            case NORTH -> "NORTH";
            case EAST -> "EAST";
            case SOUTH -> "SOUTH";
            case WEST -> "WEST";
            default -> null;
        };
    }

    private void send(Player player, String message) {
        plugin.messages().sendText(player, "editor.chat", message);
    }

    private record ConnectorDraft(String moduleId, String connectorId, int width, int height) {
    }

    private record DoorDraft(String moduleId, String doorId, String connectorId) {
    }

    public enum DeleteResult { DELETED, NOT_FOUND, INVALID_ID, EDITING, EDITOR_WORLD_IN_USE, FAILED }
}
