package com.nmdungeon.event;

import java.util.Set;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** Fired once after every participant has entered a generated dungeon. */
public final class DungeonStartEvent extends DungeonEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    public DungeonStartEvent(String dungeonId, Set<UUID> playerIds) { super(dungeonId, playerIds); }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
