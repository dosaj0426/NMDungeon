package com.nmdungeon.event;

import java.util.Set;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** Fired only for a successful full dungeon clear, never for forced end/failure. */
public final class DungeonCompleteEvent extends DungeonEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    public DungeonCompleteEvent(String dungeonId, Set<UUID> playerIds) { super(dungeonId, playerIds); }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
