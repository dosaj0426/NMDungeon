package com.nmdungeon.editor;

import java.util.Random;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

/** Creates a stable, flat construction surface at Y=63 for dungeon template editing. */
public final class FlatEditorChunkGenerator extends ChunkGenerator {
    @Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
        ChunkData data = createChunkData(world);
        data.setRegion(0, 0, 0, 16, 60, 16, Material.STONE);
        data.setRegion(0, 60, 0, 16, 63, 16, Material.DIRT);
        data.setRegion(0, 63, 0, 16, 64, 16, Material.GRASS_BLOCK);
        return data;
    }
}
