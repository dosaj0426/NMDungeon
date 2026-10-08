package com.nmdungeon.dungeon;

import com.nmdungeon.NMDungeonPlugin;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Serializes or limits expensive FAWE generation and cleanup operations on the server main scheduler. */
public final class DungeonGenerationQueue {
    private final NMDungeonPlugin plugin;
    private final ArrayDeque<QueuedGeneration> waiting = new ArrayDeque<>();
    private int running;
    private int maxConcurrent;

    public DungeonGenerationQueue(NMDungeonPlugin plugin, int maxConcurrent) {
        this.plugin = plugin;
        this.maxConcurrent = positive(maxConcurrent);
    }

    public CompletableFuture<Void> submit(Supplier<CompletableFuture<Void>> operation) {
        return submit(operation, false);
    }

    /** Cleanup is prioritized so completed maps do not accumulate while other maps are waiting to generate. */
    public CompletableFuture<Void> submitCleanup(Supplier<CompletableFuture<Void>> operation) {
        return submit(operation, true);
    }

    private CompletableFuture<Void> submit(Supplier<CompletableFuture<Void>> operation, boolean priority) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (priority) {
            waiting.addFirst(new QueuedGeneration(operation, result));
        } else {
            waiting.addLast(new QueuedGeneration(operation, result));
        }
        scheduleDrain();
        return result;
    }

    public void maxConcurrent(int maxConcurrent) {
        this.maxConcurrent = positive(maxConcurrent);
        scheduleDrain();
    }

    private void scheduleDrain() {
        plugin.getServer().getScheduler().runTask(plugin, this::startAvailable);
    }

    private void startAvailable() {
        while (running < maxConcurrent && !waiting.isEmpty()) {
            QueuedGeneration queued = waiting.removeFirst();
            running++;
            CompletableFuture<Void> operation;
            try {
                operation = queued.operation().get();
            } catch (Throwable throwable) {
                finish(queued, throwable);
                continue;
            }
            operation.whenComplete((ignored, throwable) -> finish(queued, throwable));
        }
    }

    private void finish(QueuedGeneration queued, Throwable throwable) {
        if (throwable == null) {
            queued.result().complete(null);
        } else {
            queued.result().completeExceptionally(throwable);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            running--;
            startAvailable();
        });
    }

    private int positive(int value) {
        return Math.max(1, value);
    }

    private record QueuedGeneration(Supplier<CompletableFuture<Void>> operation, CompletableFuture<Void> result) {
    }
}
