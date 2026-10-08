package com.nmdungeon.dungeon;

import java.awt.Color;
import java.util.List;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/** A per-instance fog-of-war renderer. It draws the generated plan, never the real world terrain. */
public final class DungeonExplorationMap extends MapRenderer {
    private static final int MAP_SIZE = 128;
    private static final int MARGIN = 5;
    private static final Color FOG = new Color(242, 236, 215);
    private static final Color CONNECTION = new Color(186, 173, 132);
    private static final Color CURRENT_PLAYER = new Color(255, 234, 74);
    private static final Color ICON_OUTLINE = new Color(29, 29, 34);
    private static final Color ENTRANCE_ICON = new Color(139, 235, 115);
    private static final Color EXIT_ICON = new Color(199, 142, 255);
    private static final Color BOSS_ICON = new Color(239, 64, 64);
    private static final Color BOSS_BONE = new Color(238, 221, 201);

    private final DungeonInstance instance;

    public DungeonExplorationMap(DungeonInstance instance) {
        super(true); // the player marker is individual, while the explored area is shared by the party.
        this.instance = instance;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        fill(canvas, FOG);
        if (instance.state() != DungeonState.RUNNING && instance.state() != DungeonState.READY) {
            return;
        }

        Set<Integer> revealed = instance.revealedModuleIndices();
        List<ModuleRuntime> modules = instance.modules();
        MapTransform transform = new MapTransform(instance.plan().mapBounds());
        for (ModuleRuntime module : modules) {
            if (revealed.contains(module.plan().index())) {
                drawModule(canvas, transform, module.plan());
            }
        }
        for (int first = 0; first < modules.size(); first++) {
            ModulePlan a = modules.get(first).plan();
            if (!revealed.contains(a.index())) continue;
            for (int second = first + 1; second < modules.size(); second++) {
                ModulePlan b = modules.get(second).plan();
                if (revealed.contains(b.index()) && connected(a, b)) {
                    drawLine(canvas, transform.x(centerX(a)), transform.z(centerZ(a)),
                            transform.x(centerX(b)), transform.z(centerZ(b)), CONNECTION);
                }
            }
        }
        drawModuleIcons(canvas, transform, modules, revealed);

        ModuleRuntime current = instance.findModule(player.getLocation());
        if (current != null && revealed.contains(current.plan().index())) {
            int x = transform.x(player.getLocation().getX());
            int z = transform.z(player.getLocation().getZ());
            pixel(canvas, x, z, CURRENT_PLAYER);
            pixel(canvas, x - 1, z, CURRENT_PLAYER);
            pixel(canvas, x + 1, z, CURRENT_PLAYER);
            pixel(canvas, x, z - 1, CURRENT_PLAYER);
            pixel(canvas, x, z + 1, CURRENT_PLAYER);
        }
    }

    /** Boss targets deliberately ignore fog: the party can always see the objective direction. */
    private void drawModuleIcons(MapCanvas canvas, MapTransform transform, List<ModuleRuntime> modules, Set<Integer> revealed) {
        for (ModuleRuntime module : modules) {
            ModulePlan plan = module.plan();
            int x = transform.x(centerX(plan));
            int z = transform.z(centerZ(plan));
            if (plan.type() == ModuleType.BOSS) {
                drawBossIcon(canvas, x, z);
            } else if (revealed.contains(plan.index()) && plan.type() == ModuleType.ENTRANCE) {
                drawEntranceIcon(canvas, x, z);
            } else if (revealed.contains(plan.index()) && plan.type() == ModuleType.EXIT) {
                drawExitIcon(canvas, x, z);
            }
        }
    }

    private void drawBossIcon(MapCanvas canvas, int centerX, int centerZ) {
        String[] shape = {
                "   O   ",
                "O  O  O",
                " O OOO ",
                "  OBO  ",
                " O OOO ",
                "O  O  O",
                "   O   "
        };
        drawIcon(canvas, centerX, centerZ, shape, BOSS_ICON, BOSS_BONE);
    }

    private void drawEntranceIcon(MapCanvas canvas, int centerX, int centerZ) {
        String[] shape = {"  O ", " OOO", "  O ", "  O ", " OOO"};
        drawIcon(canvas, centerX, centerZ, shape, ENTRANCE_ICON, ENTRANCE_ICON);
    }

    private void drawExitIcon(MapCanvas canvas, int centerX, int centerZ) {
        String[] shape = {" OOO ", "O   O", "O O O", "O   O", " OOO "};
        drawIcon(canvas, centerX, centerZ, shape, EXIT_ICON, EXIT_ICON);
    }

    private void drawIcon(MapCanvas canvas, int centerX, int centerZ, String[] shape, Color primary, Color accent) {
        int startZ = centerZ - shape.length / 2;
        for (int row = 0; row < shape.length; row++) {
            String line = shape[row];
            int startX = centerX - line.length() / 2;
            for (int column = 0; column < line.length(); column++) {
                char pixel = line.charAt(column);
                if (pixel == ' ') continue;
                Color color = pixel == 'B' ? accent : primary;
                // A dark one-pixel silhouette keeps icons recognisable over both fog and room colours.
                pixel(canvas, startX + column - 1, startZ + row, ICON_OUTLINE);
                pixel(canvas, startX + column + 1, startZ + row, ICON_OUTLINE);
                pixel(canvas, startX + column, startZ + row - 1, ICON_OUTLINE);
                pixel(canvas, startX + column, startZ + row + 1, ICON_OUTLINE);
                pixel(canvas, startX + column, startZ + row, color);
            }
        }
    }

    private void drawModule(MapCanvas canvas, MapTransform transform, ModulePlan plan) {
        int minX = transform.x(plan.bounds().minX());
        int maxX = transform.x(plan.bounds().maxX());
        int minZ = transform.z(plan.bounds().minZ());
        int maxZ = transform.z(plan.bounds().maxZ());
        Color fill = moduleColor(plan.type());
        Color outline = fill.brighter();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                pixel(canvas, x, z, x == minX || x == maxX || z == minZ || z == maxZ ? outline : fill);
            }
        }
    }

    private boolean connected(ModulePlan a, ModulePlan b) {
        return closestDoorDistance(a.exitDoorBlocks(), b.entranceDoorwayBlocks()) <= 6
                || closestDoorDistance(b.exitDoorBlocks(), a.entranceDoorwayBlocks()) <= 6;
    }

    private int closestDoorDistance(List<BlockPosition> first, List<BlockPosition> second) {
        int closest = Integer.MAX_VALUE;
        for (BlockPosition a : first) {
            for (BlockPosition b : second) {
                closest = Math.min(closest, Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()));
            }
        }
        return closest;
    }

    private void drawLine(MapCanvas canvas, int x0, int z0, int x1, int z1, Color color) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int error = dx - dz;
        while (true) {
            pixel(canvas, x0, z0, color);
            if (x0 == x1 && z0 == z1) return;
            int doubled = error * 2;
            if (doubled > -dz) {
                error -= dz;
                x0 += sx;
            }
            if (doubled < dx) {
                error += dx;
                z0 += sz;
            }
        }
    }

    private void fill(MapCanvas canvas, Color color) {
        for (int x = 0; x < MAP_SIZE; x++) {
            for (int z = 0; z < MAP_SIZE; z++) {
                canvas.setPixelColor(x, z, color);
            }
        }
    }

    private void pixel(MapCanvas canvas, int x, int z, Color color) {
        if (x >= 0 && x < MAP_SIZE && z >= 0 && z < MAP_SIZE) canvas.setPixelColor(x, z, color);
    }

    private double centerX(ModulePlan plan) { return (plan.bounds().minX() + plan.bounds().maxX()) / 2.0D; }
    private double centerZ(ModulePlan plan) { return (plan.bounds().minZ() + plan.bounds().maxZ()) / 2.0D; }

    private Color moduleColor(ModuleType type) {
        return switch (type) {
            case ENTRANCE -> new Color(68, 146, 82);
            case CORRIDOR -> new Color(122, 119, 105);
            case ROOM -> new Color(70, 112, 154);
            case BOSS -> new Color(166, 65, 65);
            case EXIT -> new Color(128, 86, 163);
        };
    }

    private record MapTransform(double offsetX, double offsetZ, double scale, Bounds bounds) {
        private MapTransform(Bounds bounds) {
            this((MAP_SIZE - Math.max(1, bounds.maxX() - bounds.minX() + 1)
                            * Math.min((MAP_SIZE - MARGIN * 2.0D) / Math.max(1, bounds.maxX() - bounds.minX() + 1),
                            (MAP_SIZE - MARGIN * 2.0D) / Math.max(1, bounds.maxZ() - bounds.minZ() + 1))) / 2.0D,
                    (MAP_SIZE - Math.max(1, bounds.maxZ() - bounds.minZ() + 1)
                            * Math.min((MAP_SIZE - MARGIN * 2.0D) / Math.max(1, bounds.maxX() - bounds.minX() + 1),
                            (MAP_SIZE - MARGIN * 2.0D) / Math.max(1, bounds.maxZ() - bounds.minZ() + 1))) / 2.0D,
                    Math.min((MAP_SIZE - MARGIN * 2.0D) / Math.max(1, bounds.maxX() - bounds.minX() + 1),
                            (MAP_SIZE - MARGIN * 2.0D) / Math.max(1, bounds.maxZ() - bounds.minZ() + 1)), bounds);
        }

        private int x(double worldX) { return (int) Math.round(offsetX + (worldX - bounds.minX()) * scale); }
        private int z(double worldZ) { return (int) Math.round(offsetZ + (worldZ - bounds.minZ()) * scale); }
    }
}
