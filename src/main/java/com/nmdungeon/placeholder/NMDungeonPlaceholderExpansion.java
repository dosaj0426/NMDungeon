package com.nmdungeon.placeholder;

import com.nmdungeon.NMDungeonPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

/** Optional PlaceholderAPI bridge. It is registered only when PlaceholderAPI is installed. */
public final class NMDungeonPlaceholderExpansion extends PlaceholderExpansion {
    private final NMDungeonPlugin plugin;

    public NMDungeonPlaceholderExpansion(NMDungeonPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "nmdungeon";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        return plugin.dungeonService().placeholder(player, params);
    }
}
