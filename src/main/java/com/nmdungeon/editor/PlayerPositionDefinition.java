package com.nmdungeon.editor;

/** Local, schematic-relative location for player entry, checkpoint, or start placement. */
public record PlayerPositionDefinition(int x, int y, int z, float yaw, float pitch) {
}
