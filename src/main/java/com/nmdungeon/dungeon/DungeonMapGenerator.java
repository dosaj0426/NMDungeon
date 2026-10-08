package com.nmdungeon.dungeon;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

public interface DungeonMapGenerator {
    CompletableFuture<Void> generate(DungeonInstance instance, IntConsumer progressConsumer);

    CompletableFuture<Void> clear(DungeonInstance instance);
}
