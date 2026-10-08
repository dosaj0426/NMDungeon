package com.nmdungeon.dungeon;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;

public final class DungeonPlanFactory {
    private static final int FLOOR_Y = 64;
    private static final int DEFAULT_HEIGHT = 12;

    private DungeonPlanFactory() {
    }

    /**
     * MVP layout: a guaranteed eastbound trunk and one northbound side branch.
     * The branch is deliberately fixed for now so it can be exercised every /nmdungeon start.
     */
    public static DungeonPlan createMvpPlan(World world, int slotOriginX, int slotOriginZ) {
        List<ModulePlan> modules = new ArrayList<>();
        int index = 0;
        add(modules, index++, ModuleType.ENTRANCE, slotOriginX, slotOriginZ, 32, 32, null, ModulePlan.Direction.EAST);
        add(modules, index++, ModuleType.CORRIDOR, slotOriginX + 32, slotOriginZ + 8, 16, 16, ModulePlan.Direction.WEST, ModulePlan.Direction.EAST);

        // Clearing this room opens both the trunk and the side branch.
        add(modules, index++, ModuleType.ROOM, slotOriginX + 48, slotOriginZ, 32, 32, ModulePlan.Direction.WEST,
                ModulePlan.Direction.EAST, ModulePlan.Direction.NORTH);

        // North branch: corridor -> dead-end combat room. It is optional for reaching the boss.
        add(modules, index++, ModuleType.CORRIDOR, slotOriginX + 56, slotOriginZ - 16, 16, 16, ModulePlan.Direction.SOUTH, ModulePlan.Direction.NORTH);
        add(modules, index++, ModuleType.ROOM, slotOriginX + 48, slotOriginZ - 48, 32, 32, ModulePlan.Direction.SOUTH);

        // Main path continues independently to the boss and exit.
        add(modules, index++, ModuleType.CORRIDOR, slotOriginX + 80, slotOriginZ + 8, 16, 16, ModulePlan.Direction.WEST, ModulePlan.Direction.EAST);
        add(modules, index++, ModuleType.ROOM, slotOriginX + 96, slotOriginZ, 32, 32, ModulePlan.Direction.WEST, ModulePlan.Direction.EAST);
        add(modules, index++, ModuleType.CORRIDOR, slotOriginX + 128, slotOriginZ + 8, 16, 16, ModulePlan.Direction.WEST, ModulePlan.Direction.EAST);
        add(modules, index++, ModuleType.BOSS, slotOriginX + 144, slotOriginZ - 8, 48, 48, ModulePlan.Direction.WEST, ModulePlan.Direction.EAST);
        add(modules, index, ModuleType.EXIT, slotOriginX + 192, slotOriginZ + 8, 16, 16, ModulePlan.Direction.WEST);

        int minX = modules.stream().mapToInt(module -> module.bounds().minX()).min().orElse(slotOriginX);
        int minY = modules.stream().mapToInt(module -> module.bounds().minY()).min().orElse(FLOOR_Y);
        int minZ = modules.stream().mapToInt(module -> module.bounds().minZ()).min().orElse(slotOriginZ);
        int maxX = modules.stream().mapToInt(module -> module.bounds().maxX()).max().orElse(slotOriginX);
        int maxY = modules.stream().mapToInt(module -> module.bounds().maxY()).max().orElse(FLOOR_Y + DEFAULT_HEIGHT);
        int maxZ = modules.stream().mapToInt(module -> module.bounds().maxZ()).max().orElse(slotOriginZ);
        Location start = new Location(world, slotOriginX + 4.5, FLOOR_Y + 1, slotOriginZ + 16.5);
        return new DungeonPlan(List.copyOf(modules), new Bounds(minX, minY, minZ, maxX, maxY, maxZ), start);
    }

    private static void add(List<ModulePlan> modules, int index, ModuleType type, int minX, int minZ, int width,
                            int depth, ModulePlan.Direction entrance, ModulePlan.Direction... exits) {
        Bounds bounds = new Bounds(minX, FLOOR_Y, minZ, minX + width - 1, FLOOR_Y + DEFAULT_HEIGHT, minZ + depth - 1);
        modules.add(new ModulePlan(index, type, bounds, entrance, List.of(exits)));
    }
}
