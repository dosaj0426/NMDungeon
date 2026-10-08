package com.nmdungeon.editor;

import com.nmdungeon.NMDungeonPlugin;
import com.nmdungeon.dungeon.ModuleType;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DungeonEditorListener implements Listener {
    private final NMDungeonPlugin plugin;
    private final DungeonEditSessionManager sessionManager;
    private final Map<UUID, String> anvilInputs = new HashMap<>();

    public DungeonEditorListener(NMDungeonPlugin plugin, DungeonEditSessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) return;
        DungeonEditorMenu.InputType pendingInput = sessionManager.pendingTextInput(player);
        if (pendingInput != null && event.getView() instanceof AnvilView anvilView) {
            plugin.getLogger().info("[EditorDebug] Anvil click: player=" + player.getName() + ", rawSlot="
                    + event.getRawSlot() + ", slotType=" + event.getSlotType() + ", click=" + event.getClick()
                    + ", top=" + event.getView().getTopInventory().getClass().getSimpleName());
            // The paper is only a rename input token. Never allow it (or the
            // resulting renamed paper) to move into the player's inventory.
            event.setCancelled(true);
            if (event.getSlotType() != org.bukkit.event.inventory.InventoryType.SlotType.RESULT) return;
            String input = anvilView.getRenameText();
            if (input == null || input.isBlank()) input = displayName(event.getCurrentItem());
            if (input == null || input.isBlank()) input = anvilInputs.remove(player.getUniqueId());
            if (input == null) return;
            input = ChatColor.stripColor(input).trim();
            plugin.getLogger().info("[EditorDebug] Anvil result accepted: player=" + player.getName() + ", input='" + input + "'");
            String submittedInput = input;
            // InventoryClickEvent must finish before replacing the current Anvil view.
            Bukkit.getScheduler().runTask(plugin, () -> sessionManager.completeTextInput(player, submittedInput));
            return;
        }

        if (!(event.getView().getTopInventory().getHolder() instanceof DungeonEditorMenu.EditorMenuHolder holder)) return;
        if ((holder.screen() == DungeonEditorMenu.Screen.DUNGEON_REWARDS || holder.screen() == DungeonEditorMenu.Screen.MODULE_REWARDS)
                && event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            event.setCancelled(true);
            if (event.isLeftClick() && event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) {
                if (holder.screen() == DungeonEditorMenu.Screen.DUNGEON_REWARDS) {
                    sessionManager.addDungeonRewardFromInventory(player, event.getCurrentItem());
                } else {
                    sessionManager.addModuleRewardFromInventory(player, holder.context(), event.getCurrentItem());
                }
            }
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;

        event.setCancelled(true);
        switch (holder.screen()) {
            case DASHBOARD -> handleDashboard(player, event.getRawSlot());
            case MODULES -> handleModules(player, event.getRawSlot(), event.isShiftClick());
            case MODULE_TYPE -> handleModuleType(player, holder.context(), event.getRawSlot());
            case MODULE_DETAIL -> handleModuleDetail(player, holder.context(), event.getRawSlot(), event.isRightClick());
            case CONNECTORS -> handleConnectors(player, holder.context(), event.getRawSlot(), event.isRightClick());
            case CONNECTOR_DETAIL -> handleConnectorDetail(player, holder.context(), event.getRawSlot());
            case DOORS -> handleDoors(player, holder.context(), event.getRawSlot(), event.isRightClick());
            case DOOR_CONNECTOR -> handleDoorConnector(player, holder.context(), event.getRawSlot());
            case DOOR_APPLY -> handleDoorApply(player, holder.context(), event.getRawSlot());
            case MODULE_TARGET -> handleModuleTarget(player, holder.context(), event.getRawSlot());
            case VALIDATION -> { if (event.getRawSlot() == DungeonEditorMenu.BACK) sessionManager.openDashboard(player); }
            case RULES -> handleRules(player, event.getRawSlot(), event.isRightClick(), event.isShiftClick());
            case COMBAT -> handleCombat(player, holder.context(), event.getRawSlot());
            case SPAWN_POINTS -> handleSpawnPoints(player, holder.context(), event.getRawSlot(), event.isRightClick());
            case WAVES -> handleWaves(player, holder.context(), event.getRawSlot(), event.isRightClick());
            case WAVE_DETAIL -> handleWaveDetail(player, holder.context(), event.getRawSlot(), event.isRightClick(), event.isShiftClick(),
                    event.getClick() == org.bukkit.event.inventory.ClickType.DROP || event.getClick() == org.bukkit.event.inventory.ClickType.CONTROL_DROP);
            case WAVE_SPAWN_PICKER -> handleWaveSpawnPicker(player, holder.context(), event.getRawSlot());
            case BOSS_SPAWN_PICKER -> handleBossSpawnPicker(player, holder.context(), event.getRawSlot());
            case DUNGEON_REWARDS -> handleDungeonRewardItems(player, event.getRawSlot(), event.isRightClick());
            case MODULE_REWARDS -> handleModuleRewards(player, holder.context(), event.getRawSlot(), event.isRightClick());
            case MODULE_CLEAR_COMMANDS -> handleModuleClearCommands(player, holder.context(), event.getRawSlot(), event.isRightClick());
            default -> { }
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (event.getViewers().isEmpty()) {
            return;
        }
        if (!(event.getViewers().getFirst() instanceof org.bukkit.entity.Player player)) return;
        if (sessionManager.pendingTextInput(player) == null) return;
        event.getInventory().setRepairCost(0);
        if (event.getResult() == null || !event.getResult().hasItemMeta() || !event.getResult().getItemMeta().hasDisplayName()) return;
        String input = ChatColor.stripColor(event.getResult().getItemMeta().getDisplayName()).trim();
        anvilInputs.put(player.getUniqueId(), input);
        plugin.getLogger().info("[EditorDebug] Anvil result prepared: player=" + player.getName() + ", input='" + input + "'");
    }

    @EventHandler
    public void onAnvilClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof org.bukkit.entity.Player player)
                || !(event.getView() instanceof AnvilView)) return;
        // A successful click schedules its completion before the close event runs; it clears the state itself.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory() instanceof AnvilView) return;
            plugin.getLogger().info("[EditorDebug] Anvil closed without submission: player=" + player.getName());
            purgeAnvilInputTokens(player);
            sessionManager.clearPendingTextInput(player);
            anvilInputs.remove(player.getUniqueId());
        });
    }

    private void purgeAnvilInputTokens(org.bukkit.entity.Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (DungeonEditorMenu.isAnvilInputToken(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
        if (DungeonEditorMenu.isAnvilInputToken(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    private String displayName(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return null;
        return item.getItemMeta().getDisplayName();
    }

    private void handleDashboard(org.bukkit.entity.Player player, int slot) {
        switch (slot) {
            case DungeonEditorMenu.SAVE -> sessionManager.save(player);
            case DungeonEditorMenu.DISCARD -> sessionManager.discard(player);
            case DungeonEditorMenu.EXIT -> sessionManager.exit(player);
            case DungeonEditorMenu.VALIDATE -> sessionManager.openValidation(player);
            case DungeonEditorMenu.MODULES -> sessionManager.openModules(player);
            case 14 -> sessionManager.openRules(player);
            case DungeonEditorMenu.CONNECTORS -> sessionManager.openModuleTarget(player, "connectors");
            case DungeonEditorMenu.DOORS -> sessionManager.openModuleTarget(player, "doors");
            case DungeonEditorMenu.MARKERS -> sessionManager.openModuleTarget(player, "combat");
            case 16 -> sessionManager.openDungeonRewards(player);
            default -> { }
        }
    }

    private void handleModules(org.bukkit.entity.Player player, int slot, boolean shiftClick) {
        if (slot == DungeonEditorMenu.BACK) sessionManager.openDashboard(player);
        else if (slot == DungeonEditorMenu.ADD) sessionManager.openModuleIdInput(player);
        else if (slot >= 0 && slot < 45) {
            DungeonEditSession session = sessionManager.session(player);
            if (session == null) return;
            ModuleDefinition module = session.definition().modules().values().stream().skip(slot).findFirst().orElse(null);
            if (module != null) {
                if (shiftClick) sessionManager.teleportToModuleOrigin(player, module.id());
                else sessionManager.openModuleDetail(player, module.id());
            }
        }
    }

    private void handleModuleTarget(org.bukkit.entity.Player player, String target, int slot) {
        if (slot == DungeonEditorMenu.BACK) {
            sessionManager.openDashboard(player);
            return;
        }
        if (slot < 0 || slot >= 45) return;
        DungeonEditSession session = sessionManager.session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().values().stream().skip(slot).findFirst().orElse(null);
        if (module == null) return;
        switch (target) {
            case "connectors" -> sessionManager.openConnectors(player, module.id());
            case "doors" -> sessionManager.openDoors(player, module.id());
            default -> sessionManager.openCombat(player, module.id());
        }
    }

    private void handleModuleType(org.bukkit.entity.Player player, String moduleId, int slot) {
        if (slot == 22) {
            sessionManager.openModules(player);
            return;
        }
        int typeIndex = slot - 11;
        ModuleType[] types = ModuleType.values();
        if (typeIndex >= 0 && typeIndex < types.length) sessionManager.createModuleFromGui(player, moduleId, types[typeIndex]);
    }

    private void handleModuleDetail(org.bukkit.entity.Player player, String moduleId, int slot, boolean rightClick) {
        switch (slot) {
            case 10 -> sessionManager.exportModule(player, moduleId);
            case 11 -> sessionManager.teleportToModuleOrigin(player, moduleId);
            case 12 -> sessionManager.adjustModuleWeight(player, moduleId, -1);
            case 14 -> sessionManager.adjustModuleWeight(player, moduleId, 1);
            case 20 -> sessionManager.toggleModuleRotation(player, moduleId, 0);
            case 21 -> sessionManager.toggleModuleRotation(player, moduleId, 90);
            case 22 -> sessionManager.toggleModuleRotation(player, moduleId, 180);
            case 23 -> sessionManager.toggleModuleRotation(player, moduleId, 270);
            case 16 -> sessionManager.openConnectors(player, moduleId);
            case 17 -> sessionManager.openDoors(player, moduleId);
            case 18 -> sessionManager.openCombat(player, moduleId);
            case 24 -> sessionManager.adjustModuleInstanceLimit(player, moduleId, true, rightClick ? -1 : 1);
            case 25 -> sessionManager.adjustModuleInstanceLimit(player, moduleId, false, rightClick ? -1 : 1);
            case 26 -> sessionManager.openModules(player);
            case 28 -> sessionManager.setModulePlayerPoint(player, moduleId, true);
            case 29 -> sessionManager.setModulePlayerPoint(player, moduleId, false);
            case 30 -> sessionManager.clearModulePlayerPoint(player, moduleId, true);
            case 31 -> sessionManager.clearModulePlayerPoint(player, moduleId, false);
            case 32 -> sessionManager.openModuleRewards(player, moduleId);
            case 33 -> sessionManager.openModuleClearCommands(player, moduleId);
            default -> { }
        }
    }

    private void handleConnectors(org.bukkit.entity.Player player, String moduleId, int slot, boolean rightClick) {
        if (slot == DungeonEditorMenu.BACK) {
            sessionManager.openModuleDetail(player, moduleId);
            return;
        }
        if (slot == DungeonEditorMenu.ADD) {
            sessionManager.openConnectorIdInput(player, moduleId);
            return;
        }
        if (slot < 0 || slot >= 45) return;
        DungeonEditSession session = sessionManager.session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) return;
        ConnectorDefinition connector = module.connectors().stream().skip(slot).findFirst().orElse(null);
        if (connector == null) return;
        if (rightClick) sessionManager.deleteConnectorFromGui(player, moduleId, connector.id());
        else sessionManager.openConnectorDetail(player, moduleId, connector.id());
    }

    private void handleConnectorDetail(org.bukkit.entity.Player player, String context, int slot) {
        String[] ids = context.split("\\u0000", 2);
        if (ids.length != 2) return;
        String moduleId = ids[0];
        String connectorId = ids[1];
        switch (slot) {
            case 10 -> sessionManager.changeConnectorSize(player, moduleId, connectorId, -1, 0);
            case 12 -> sessionManager.changeConnectorSize(player, moduleId, connectorId, 1, 0);
            case 14 -> sessionManager.changeConnectorSize(player, moduleId, connectorId, 0, -1);
            case 16 -> sessionManager.changeConnectorSize(player, moduleId, connectorId, 0, 1);
            case 18 -> sessionManager.setConnectorPlayerEntry(player, moduleId, connectorId);
            case 19 -> sessionManager.teleportToConnectorPlayerEntry(player, moduleId, connectorId);
            case 20 -> sessionManager.toggleConnectorBranch(player, moduleId, connectorId);
            case 21 -> sessionManager.clearConnectorPlayerEntry(player, moduleId, connectorId);
            case 22 -> sessionManager.deleteConnectorFromGui(player, moduleId, connectorId);
            case 26 -> sessionManager.openConnectors(player, moduleId);
            default -> { }
        }
    }

    private void handleDoors(org.bukkit.entity.Player player, String moduleId, int slot, boolean rightClick) {
        if (slot == DungeonEditorMenu.BACK) {
            sessionManager.openModuleDetail(player, moduleId);
            return;
        }
        if (slot == DungeonEditorMenu.ADD) {
            sessionManager.openDoorIdInput(player, moduleId);
            return;
        }
        if (!rightClick || slot < 0 || slot >= 45) return;
        DungeonEditSession session = sessionManager.session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) return;
        DoorDefinition door = module.doors().stream().skip(slot).findFirst().orElse(null);
        if (door != null) {
            sessionManager.removeDoor(player, moduleId, door.id());
            sessionManager.openDoors(player, moduleId);
        }
    }

    private void handleDoorConnector(org.bukkit.entity.Player player, String context, int slot) {
        String[] ids = context.split("\\u0000", 2);
        if (ids.length != 2) return;
        String moduleId = ids[0];
        String doorId = ids[1];
        if (slot == 22) {
            sessionManager.openDoors(player, moduleId);
            return;
        }
        if (slot < 0 || slot >= 18) return;
        DungeonEditSession session = sessionManager.session(player);
        if (session == null) return;
        ModuleDefinition module = session.definition().modules().get(moduleId);
        if (module == null) return;
        ConnectorDefinition connector = module.connectors().stream().skip(slot).findFirst().orElse(null);
        if (connector != null) sessionManager.chooseDoorConnector(player, moduleId, doorId, connector.id());
    }

    private void handleDoorApply(org.bukkit.entity.Player player, String context, int slot) {
        String[] ids = context.split("\\u0000", 3);
        if (ids.length != 3) return;
        if (slot == 13) sessionManager.applyPendingDoor(player);
        else if (slot == 15) sessionManager.cancelPendingDoor(player, ids[0]);
    }

    private void handleRules(org.bukkit.entity.Player player, int slot, boolean rightClick, boolean shiftClick) {
        if (slot == 22) {
            sessionManager.openDashboard(player);
            return;
        }
        if (slot == 17) {
            sessionManager.toggleCombatRoomCorridorRule(player);
            return;
        }
        if (slot == 18) {
            sessionManager.toggleRequireAllModulesClearedForBoss(player);
            return;
        }
        String rule = switch (slot) {
            case 10 -> "min-corridors";
            case 11 -> "max-corridors";
            case 13 -> "min-rooms";
            case 14 -> "max-rooms";
            case 16 -> "max-branches";
            case 19 -> "max-lives";
            case 20 -> "time-limit-seconds";
            default -> null;
        };
        if (rule == null) return;
        DungeonEditSession session = sessionManager.session(player);
        if (session == null) return;
        int current = switch (rule) {
            case "min-corridors" -> session.definition().minCorridors();
            case "max-corridors" -> session.definition().maxCorridors();
            case "min-rooms" -> session.definition().minRooms();
            case "max-rooms" -> session.definition().maxRooms();
            case "max-branches" -> session.definition().maxBranches();
            case "max-lives" -> session.definition().maxLives();
            case "time-limit-seconds" -> session.definition().timeLimitSeconds();
            default -> 0;
        };
        int amount = switch (rule) {
            case "time-limit-seconds" -> shiftClick ? 300 : 60;
            default -> shiftClick ? 5 : 1;
        };
        sessionManager.setGenerationRule(player, rule, current + (rightClick ? -amount : amount));
        sessionManager.openRules(player);
    }

    private void handleCombat(org.bukkit.entity.Player player, String moduleId, int slot) {
        switch (slot) {
            case 10 -> sessionManager.openSpawnPoints(player, moduleId);
            case 13 -> sessionManager.openWaves(player, moduleId);
            case 15 -> sessionManager.adjustBossLevel(player, moduleId, -1.0D);
            case 16 -> sessionManager.openBossSpawnPicker(player, moduleId);
            case 17 -> sessionManager.adjustBossLevel(player, moduleId, 1.0D);
            case 22 -> sessionManager.openModuleDetail(player, moduleId);
            default -> { }
        }
    }

    private void handleSpawnPoints(org.bukkit.entity.Player player, String moduleId, int slot, boolean rightClick) {
        if (slot == DungeonEditorMenu.BACK) { sessionManager.openCombat(player, moduleId); return; }
        if (slot == DungeonEditorMenu.ADD) { sessionManager.openSpawnIdInput(player, moduleId); return; }
        if (slot < 0 || slot >= 45) return;
        DungeonEditSession session = sessionManager.session(player); ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        SpawnPointDefinition point = module == null ? null : module.spawnPoints().stream().skip(slot).findFirst().orElse(null);
        if (point == null) return;
        if (rightClick) sessionManager.deleteSpawnPoint(player, moduleId, point.id());
        else sessionManager.teleportToSpawnPoint(player, moduleId, point.id());
    }

    private void handleWaves(org.bukkit.entity.Player player, String moduleId, int slot, boolean rightClick) {
        if (slot == DungeonEditorMenu.BACK) { sessionManager.openCombat(player, moduleId); return; }
        if (slot == DungeonEditorMenu.ADD) { sessionManager.openWaveIdInput(player, moduleId); return; }
        if (slot < 0 || slot >= 45) return;
        DungeonEditSession session = sessionManager.session(player); ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        WaveDefinition wave = module == null ? null : module.waves().stream().skip(slot).findFirst().orElse(null);
        if (wave == null) return;
        if (rightClick) sessionManager.deleteWave(player, moduleId, wave.id());
        else sessionManager.openWaveDetail(player, moduleId, wave.id());
    }

    private void handleWaveDetail(org.bukkit.entity.Player player, String context, int slot, boolean rightClick, boolean shiftClick, boolean dropClick) {
        String[] ids = context.split("\u0000", 2); if (ids.length != 2) return;
        if (slot == DungeonEditorMenu.BACK) { sessionManager.openWaves(player, ids[0]); return; }
        if (slot == DungeonEditorMenu.ADD) { sessionManager.openWaveSpawnPicker(player, ids[0], ids[1]); return; }
        if (slot < 0 || slot >= 45) return;
        if (dropClick) { sessionManager.deleteWaveSpawn(player, ids[0], ids[1], slot); return; }
        if (shiftClick) sessionManager.adjustWaveSpawn(player, ids[0], ids[1], slot, 0, rightClick ? -1.0D : 1.0D);
        else sessionManager.adjustWaveSpawn(player, ids[0], ids[1], slot, rightClick ? -1 : 1, 0.0D);
    }

    private void handleWaveSpawnPicker(org.bukkit.entity.Player player, String context, int slot) {
        String[] ids = context.split("\u0000", 2); if (ids.length != 2) return;
        if (slot == 22) { sessionManager.openWaveDetail(player, ids[0], ids[1]); return; }
        DungeonEditSession session = sessionManager.session(player); ModuleDefinition module = session == null ? null : session.definition().modules().get(ids[0]);
        SpawnPointDefinition point = module == null || slot < 0 || slot >= 18 ? null : module.spawnPoints().stream().skip(slot).findFirst().orElse(null);
        if (point != null) sessionManager.selectWaveSpawnPoint(player, ids[0], ids[1], point.id());
    }

    private void handleBossSpawnPicker(org.bukkit.entity.Player player, String moduleId, int slot) {
        if (slot == 22) { sessionManager.openCombat(player, moduleId); return; }
        DungeonEditSession session = sessionManager.session(player); ModuleDefinition module = session == null ? null : session.definition().modules().get(moduleId);
        SpawnPointDefinition point = module == null || slot < 0 || slot >= 18 ? null : module.spawnPoints().stream().skip(slot).findFirst().orElse(null);
        if (point != null) sessionManager.selectBossSpawnPoint(player, moduleId, point.id());
    }

    private void handleDungeonRewardItems(org.bukkit.entity.Player player, int slot, boolean rightClick) {
        if (slot == 22) { sessionManager.openDashboard(player); return; }
        if (slot < 0 || slot >= 18) return;
        if (rightClick) sessionManager.removeDungeonReward(player, slot);
        else sessionManager.openDungeonRewardChanceInput(player, slot);
    }

    private void handleModuleRewards(org.bukkit.entity.Player player, String moduleId, int slot, boolean rightClick) {
        if (slot == DungeonEditorMenu.BACK) { sessionManager.openModuleDetail(player, moduleId); return; }
        if (slot < 0 || slot >= 18) return;
        if (rightClick) sessionManager.removeModuleReward(player, moduleId, slot);
        else sessionManager.openModuleRewardChanceInput(player, moduleId, slot);
    }

    private void handleModuleClearCommands(org.bukkit.entity.Player player, String moduleId, int slot, boolean rightClick) {
        if (slot == DungeonEditorMenu.BACK) { sessionManager.openModuleDetail(player, moduleId); return; }
        if (slot == DungeonEditorMenu.ADD) { sessionManager.openModuleClearCommandInput(player, moduleId); return; }
        if (rightClick && slot >= 0 && slot < 45) sessionManager.removeModuleClearCommand(player, moduleId, slot);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!EditorBook.isEditorBook(plugin, event.getItem())) return;
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        sessionManager.resumeEditorBook(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onConnectorPlacement(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getBlockFace() == null) return;
        if (!sessionManager.hasPendingConnectorPlacement(event.getPlayer())) return;
        if (EditorBook.isEditorBook(plugin, event.getItem())) return;
        event.setCancelled(true);
        sessionManager.placePendingConnector(event.getPlayer(), event.getClickedBlock(), event.getBlockFace());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (EditorBook.isEditorBook(plugin, event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player
                && sessionManager.pendingTextInput(player) != null
                && event.getView() instanceof AnvilView) {
            event.setCancelled(true);
            return;
        }
        if (EditorBook.isEditorBook(plugin, event.getOldCursor())) event.setCancelled(true);
        if (event.getView().getTopInventory().getHolder() instanceof DungeonEditorMenu.EditorMenuHolder holder
                && (holder.screen() == DungeonEditorMenu.Screen.DUNGEON_REWARDS || holder.screen() == DungeonEditorMenu.Screen.MODULE_REWARDS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEditorBookMove(InventoryClickEvent event) {
        if (EditorBook.isEditorBook(plugin, event.getCurrentItem()) || EditorBook.isEditorBook(plugin, event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessionManager.closeOnDisconnect(event.getPlayer());
    }
}
