package com.nmdungeon.dungeon;

import org.bukkit.Location;

public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public boolean contains(Location location) {
        return location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockY() >= minY && location.getBlockY() <= maxY
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }
}
