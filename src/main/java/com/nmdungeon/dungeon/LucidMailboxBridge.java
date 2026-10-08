package com.nmdungeon.dungeon;

import com.nmdungeon.NMDungeonPlugin;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/** Optional, reflection-only bridge so NMDungeon can use Lucid mail without bundling Lucid's API classes. */
final class LucidMailboxBridge {
    private final NMDungeonPlugin plugin;

    LucidMailboxBridge(NMDungeonPlugin plugin) {
        this.plugin = plugin;
    }

    boolean send(UUID recipient, ItemStack item, String source) {
        if (Bukkit.getPluginManager().getPlugin("Lucid") == null) return false;
        try {
            Class<?> apiType = Class.forName("kr.lucid.api.MailApi");
            Object api = Bukkit.getServicesManager().load(apiType);
            if (api == null) return false; // Lucid can be installed but still be connecting its database.
            Method send = apiType.getMethod("send", UUID.class, ItemStack.class, String.class);
            Object result = send.invoke(api, recipient, item.clone(), source);
            if (result instanceof CompletableFuture<?> future) {
                future.whenComplete((mailId, error) -> {
                    if (error != null) plugin.getLogger().warning("Lucid mailbox reward delivery failed: " + error.getMessage());
                });
            }
            return true;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Lucid mailbox API is unavailable: " + exception.getMessage());
            return false;
        }
    }
}
