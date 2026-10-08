package com.nmdungeon.editor;

import java.util.List;

public record ConnectorDefinition(String id, String facing, int x, int y, int z, int width, int height,
                                  boolean allowBranch, List<String> tags, PlayerPositionDefinition playerEntryPoint) {
    public ConnectorDefinition(String id, String facing, int x, int y, int z, int width, int height,
                               boolean allowBranch, List<String> tags) {
        this(id, facing, x, y, z, width, height, allowBranch, tags, null);
    }
}
