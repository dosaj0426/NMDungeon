package com.nmdungeon.dungeon;

import java.util.Random;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

public final class VoidChunkGenerator extends ChunkGenerator {
    @Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
        return createChunkData(world);
    }
}
