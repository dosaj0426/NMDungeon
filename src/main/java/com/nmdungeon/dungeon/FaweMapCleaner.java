package com.nmdungeon.dungeon;

import com.nmdungeon.NMDungeonPlugin;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.util.concurrent.CompletableFuture;

/** Clears only the placed module cuboids through FAWE, never the full enclosing map bounds. */
public final class FaweMapCleaner {
    private FaweMapCleaner() {
    }

    public static CompletableFuture<Void> clear(NMDungeonPlugin plugin, DungeonInstance instance) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(instance.world()))) {
                for (ModulePlan module : instance.plan().modules()) {
                    Bounds bounds = module.bounds();
                    CuboidRegion region = new CuboidRegion(
                            BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ()),
                            BlockVector3.at(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
                    session.setBlocks((Region) region, BlockTypes.AIR.getDefaultState());
                }
                session.flushSession();
                result.complete(null);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }
}
