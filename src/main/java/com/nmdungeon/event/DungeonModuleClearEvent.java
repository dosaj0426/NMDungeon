package com.nmdungeon.event;

import java.util.Set;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** Fired once when a module has been cleared. */
public final class DungeonModuleClearEvent extends DungeonEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String moduleId;
    public DungeonModuleClearEvent(String dungeonId, String moduleId, Set<UUID> playerIds) { super(dungeonId, playerIds); this.moduleId = moduleId == null ? "" : moduleId; }
    public String getModuleId() { return moduleId; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
