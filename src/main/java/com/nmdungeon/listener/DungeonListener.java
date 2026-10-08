package com.nmdungeon.listener;

import com.nmdungeon.dungeon.DungeonService;
import com.nmdungeon.message.MessageService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class DungeonListener implements Listener {
    private final DungeonService dungeonService;
    private final MessageService messages;

    public DungeonListener(DungeonService dungeonService, MessageService messages) {
        this.dungeonService = dungeonService;
        this.messages = messages;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (dungeonService.isDoorAnimationBlocking(event.getPlayer(), event.getTo())) {
            event.setCancelled(true);
            return;
        }
        dungeonService.onPlayerMove(event.getPlayer(), event.getFrom(), event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (dungeonService.onDoorButtonInteract(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        dungeonService.onPlayerRespawn(event.getPlayer(), event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        dungeonService.onPlayerQuit(event.getPlayer());
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        dungeonService.onEntityDeath(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        dungeonService.onPlayerDeath(event.getEntity());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (dungeonService.isRunningDungeonPlayer(event.getPlayer())) {
            event.setCancelled(true);
            messages.actionBar(event.getPlayer(), "actionbar.block-break-denied");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (dungeonService.isRunningDungeonPlayer(event.getPlayer())) {
            event.setCancelled(true);
            messages.actionBar(event.getPlayer(), "actionbar.block-place-denied");
        }
    }
}
