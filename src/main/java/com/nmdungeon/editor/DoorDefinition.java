package com.nmdungeon.editor;

import com.nmdungeon.dungeon.BlockPosition;

public record DoorDefinition(String id, String connectorId, String conditionType, BlockPosition pos1,
                             BlockPosition pos2) {
}
