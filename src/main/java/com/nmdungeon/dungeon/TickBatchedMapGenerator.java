package com.nmdungeon.dungeon;

import com.nmdungeon.NMDungeonPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Development generator for the MVP. It writes a small amount of blocks per tick,
 * then can be replaced by a FAWE schematic implementation without changing the lifecycle.
 */
public final class TickBatchedMapGenerator implements DungeonMapGenerator {
    private final NMDungeonPlugin plugin;
    private final int blocksPerTick;

    public TickBatchedMapGenerator(NMDungeonPlugin plugin, int blocksPerTick) {
        this.plugin = plugin;
        this.blocksPerTick = blocksPerTick;
    }

    @Override
    public CompletableFuture<Void> generate(DungeonInstance instance, IntConsumer progressConsumer) {
        List<BlockChange> changes = new ArrayList<>();
        for (ModulePlan module : instance.plan().modules()) {
            appendModule(changes, module);
        }
        progressConsumer.accept(0);
        return apply(instance.world(), changes, progressConsumer);
    }

    @Override
    public CompletableFuture<Void> clear(DungeonInstance instance) {
        return FaweMapCleaner.clear(plugin, instance);
    }

    private void appendModule(List<BlockChange> changes, ModulePlan module) {
        Bounds bounds = module.bounds();
        Material wall = switch (module.type()) {
            case ENTRANCE -> Material.PRISMARINE_BRICKS;
            case CORRIDOR -> Material.STONE_BRICKS;
            case ROOM -> Material.DEEPSLATE_BRICKS;
            case BOSS -> Material.POLISHED_BLACKSTONE_BRICKS;
            case EXIT -> Material.END_STONE_BRICKS;
        };

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                changes.add(new BlockChange(new BlockPosition(x, bounds.minY(), z), Material.POLISHED_DEEPSLATE));
            }
        }

        for (int y = bounds.minY() + 1; y <= bounds.maxY(); y++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                changes.add(new BlockChange(new BlockPosition(x, y, bounds.minZ()), wall));
                changes.add(new BlockChange(new BlockPosition(x, y, bounds.maxZ()), wall));
            }
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                changes.add(new BlockChange(new BlockPosition(bounds.minX(), y, z), wall));
                changes.add(new BlockChange(new BlockPosition(bounds.maxX(), y, z), wall));
            }
        }

        for (BlockPosition entrance : module.entranceDoorwayBlocks()) {
            changes.add(new BlockChange(entrance, Material.AIR));
        }
        for (BlockPosition door : module.exitDoorBlocks()) {
            changes.add(new BlockChange(door, Material.IRON_BARS));
        }
    }

    private CompletableFuture<Void> apply(World world, List<BlockChange> changes, IntConsumer progressConsumer) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        new BukkitRunnable() {
            private int cursor;

            @Override
            public void run() {
                int processed = 0;
                while (cursor < changes.size() && processed++ < blocksPerTick) {
                    BlockChange change = changes.get(cursor++);
                    world.getBlockAt(change.position().x(), change.position().y(), change.position().z())
                            .setType(change.material(), false);
                }
                progressConsumer.accept(changes.isEmpty() ? 100 : cursor * 100 / changes.size());
                if (cursor >= changes.size()) {
                    cancel();
                    result.complete(null);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return result;
    }

    private record BlockChange(BlockPosition position, Material material) {
    }
}
