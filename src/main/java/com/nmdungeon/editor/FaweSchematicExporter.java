package com.nmdungeon.editor;

import com.nmdungeon.NMDungeonPlugin;
import com.nmdungeon.dungeon.BlockPosition;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Exports a player's FAWE selection to NMDungeon's module schematic directory. */
public final class FaweSchematicExporter {
    private final NMDungeonPlugin plugin;
    private final DungeonRepository repository;

    public FaweSchematicExporter(NMDungeonPlugin plugin, DungeonRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    public void export(Player player, DungeonEditSession session, ModuleDefinition module) {
        Plugin installedPlugin = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        if (!(installedPlugin instanceof WorldEditPlugin worldEditPlugin)) {
            send(player, "§cFAWE가 활성화되어 있지 않습니다. 서버를 재시작해 주세요.");
            return;
        }
        if (!player.getWorld().equals(session.world())) {
            send(player, "§c현재 던전의 편집 월드에서만 schematic을 내보낼 수 있습니다.");
            return;
        }

        CuboidRegion region;
        try {
            LocalSession localSession = worldEditPlugin.getSession(player);
            Region selection = localSession.getSelection(BukkitAdapter.adapt(player.getWorld()));
            region = new CuboidRegion(BukkitAdapter.adapt(player.getWorld()), selection.getMinimumPoint(), selection.getMaximumPoint());
        } catch (IncompleteRegionException exception) {
            send(player, "§cFAWE 완드로 내보낼 모듈의 두 모서리를 먼저 선택하세요.");
            return;
        }

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int sizeX = max.x() - min.x() + 1;
        int sizeY = max.y() - min.y() + 1;
        int sizeZ = max.z() - min.z() + 1;
        int maxSizeX = positiveConfig("max-module-size.x", 64);
        int maxSizeY = positiveConfig("max-module-size.y", 32);
        int maxSizeZ = positiveConfig("max-module-size.z", 64);
        if (sizeX > maxSizeX || sizeY > maxSizeY || sizeZ > maxSizeZ) {
            send(player, "§c선택 영역이 모듈 최대 크기(" + maxSizeX + "×" + maxSizeY + "×" + maxSizeZ + ")를 초과했습니다: "
                    + sizeX + "×" + sizeY + "×" + sizeZ);
            return;
        }

        module.setSize(sizeX, sizeY, sizeZ);
        module.setTemplateOrigin(new BlockPosition(min.x(), min.y(), min.z()));
        session.markDirty();
        send(player, "§eFAWE가 schematic을 저장 중입니다...");
        org.bukkit.World sourceWorld = player.getWorld();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> exportAsync(session, module, sourceWorld, region, min, player.getUniqueId()));
    }

    private void exportAsync(DungeonEditSession session, ModuleDefinition module, org.bukkit.World sourceWorld,
                             CuboidRegion region, BlockVector3 origin, java.util.UUID playerId) {
        try {
            File output = repository.schematicFile(session.definition().id(), module.id());
            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            clipboard.setOrigin(origin);
            ForwardExtentCopy copy = new ForwardExtentCopy(BukkitAdapter.adapt(sourceWorld), region, clipboard, origin);
            copy.setCopyingEntities(false);
            Operations.complete(copy);
            try (OutputStream stream = Files.newOutputStream(output.toPath());
                 ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(stream)) {
                writer.write(clipboard);
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    send(player, "§a모듈 schematic 저장 완료: §f" + output.getName());
                    send(player, "§7크기: " + module.sizeX() + "×" + module.sizeY() + "×" + module.sizeZ() + " | /nmdungeon edit save 로 설정을 저장하세요.");
                }
            });
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("FAWE schematic export failed: " + exception.getMessage());
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    send(player, "§cSchematic 저장에 실패했습니다. 콘솔 로그를 확인하세요.");
                }
            });
        }
    }

    private int positiveConfig(String path, int fallback) {
        int value = plugin.getConfig().getInt(path, fallback);
        return value > 0 ? value : fallback;
    }

    private void send(Player player, String message) {
        plugin.messages().sendText(player, "fawe.chat", message);
    }
}
