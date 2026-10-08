package com.nmdungeon.editor;

import com.nmdungeon.dungeon.BlockPosition;

public record MarkerDefinition(String id, String type, BlockPosition position) {
}
