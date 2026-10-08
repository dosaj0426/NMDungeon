package com.nmdungeon.dungeon;

import com.nmdungeon.NMDungeonPlugin;
import com.nmdungeon.editor.DungeonRepository;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.session.ClipboardHolder;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import org.bukkit.Material;

/** Pastes exported Sponge schematics asynchronously through FAWE. */
public final class FaweSchematicMapGenerator implements DungeonMapGenerator {
    private final NMDungeonPlugin plugin;
    private final DungeonRepository repository;

    public FaweSchematicMapGenerator(NMDungeonPlugin plugin, DungeonRepository repository, int blocksPerTick) {
        this.plugin = plugin;
        this.repository = repository;
    }

    @Override
    public CompletableFuture<Void> generate(DungeonInstance instance, IntConsumer progressConsumer) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        progressConsumer.accept(0);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                paste(instance, progressConsumer);
                result.complete(null);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<Void> clear(DungeonInstance instance) {
        return FaweMapCleaner.clear(plugin, instance);
    }

    private void paste(DungeonInstance instance, IntConsumer progressConsumer) throws Exception {
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(instance.world()))) {
            int pasted = 0;
            int moduleCount = instance.plan().modules().size();
            for (ModulePlan module : instance.plan().modules()) {
                File schematic = repository.findSchematicFile(instance.dungeonId(), module.sourceModuleId());
                ClipboardFormat format = ClipboardFormats.findByFile(schematic);
                if (format == null || !schematic.isFile()) {
                    throw new IllegalStateException("schematic 파일을 읽을 수 없습니다: " + schematic.getName());
                }
                try (InputStream input = Files.newInputStream(schematic.toPath()); ClipboardReader reader = format.getReader(input)) {
                    Clipboard clipboard = reader.read();
                    ClipboardHolder holder = new ClipboardHolder(clipboard);
                    if (module.rotation() != 0) {
                        holder.setTransform(new AffineTransform().rotateY(module.rotation()));
                    }
                    Operations.complete(holder.createPaste(session)
                            .to(BlockVector3.at(module.pasteOrigin().x(), module.pasteOrigin().y(), module.pasteOrigin().z()))
                            .ignoreAirBlocks(false)
                            .copyEntities(false)
                            .build());
                }
                pasted++;
                progressConsumer.accept(Math.min(85, pasted * 85 / Math.max(1, moduleCount)));
            }
            session.flushSession();
        }
        progressConsumer.accept(90);

        // Read the actual pasted blocks in a new session. This preserves the authored door state
        // (for example redstone blocks) instead of reading a pre-flush air state from the paste queue.
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(instance.world()))) {
            for (ModuleRuntime module : instance.modules()) {
                for (BlockPosition doorway : module.lockDoorBlocks()) {
                    module.captureClosedDoorBlock(doorway, session.getBlock(BlockVector3.at(doorway.x(), doorway.y(), doorway.z())).getAsString());
                }
                for (BlockPosition doorway : module.plan().entranceDoorwayBlocks()) {
                    session.setBlock(BlockVector3.at(doorway.x(), doorway.y(), doorway.z()),
                            com.sk89q.worldedit.world.block.BlockTypes.AIR.getDefaultState());
                }
                for (BlockPosition connector : module.plan().sealedConnectorBlocks()) {
                    session.setBlock(BlockVector3.at(connector.x(), connector.y(), connector.z()),
                            com.sk89q.worldedit.world.block.BlockTypes.BEDROCK.getDefaultState());
                }
            }
            session.flushSession();
        }
        progressConsumer.accept(100);
    }
}
