package com.nmdungeon.editor;

import com.nmdungeon.dungeon.BlockPosition;

public record SpawnPointDefinition(String id, BlockPosition position) {
}
