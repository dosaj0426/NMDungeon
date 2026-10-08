package com.nmdungeon.event;

import java.util.Set;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Public integration event base for a running NMDungeon instance. */
public abstract class DungeonEvent extends Event {
    private final String dungeonId;
    private final Set<UUID> playerIds;

    protected DungeonEvent(String dungeonId, Set<UUID> playerIds) {
        this.dungeonId = dungeonId == null || dungeonId.isBlank() ? "mvp" : dungeonId;
        this.playerIds = Set.copyOf(playerIds);
    }

    /** Configured dungeon ID. The legacy MVP test dungeon is exposed as {@code mvp}. */
    public String getDungeonId() { return dungeonId; }
    /** Immutable UUID set of all instance participants at the time of the event. */
    public Set<UUID> getPlayerIds() { return playerIds; }
}
