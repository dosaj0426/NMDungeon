package com.nmdungeon.editor;

import java.util.UUID;
import org.bukkit.World;

public final class DungeonEditSession {
    private final UUID editorId;
    private final DungeonDefinition definition;
    private final World world;
    private boolean dirty;

    public DungeonEditSession(UUID editorId, DungeonDefinition definition, World world) {
        this.editorId = editorId;
        this.definition = definition;
        this.world = world;
    }

    public UUID editorId() { return editorId; }
    public DungeonDefinition definition() { return definition; }
    public World world() { return world; }
    public boolean dirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void markSaved() { dirty = false; }
}
