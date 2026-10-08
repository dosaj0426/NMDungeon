package com.nmdungeon.dungeon;

import org.bukkit.Location;
import org.bukkit.World;

/** World-space player location calculated after a schematic module has been rotated and placed. */
public record PlayerEntryPoint(double x, double y, double z, float yaw, float pitch) {
    public Location toLocation(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }
}
