package com.nmdungeon.message;

import com.nmdungeon.NMDungeonPlugin;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Loads player-facing text from message.yml and applies per-key visibility switches. */
public final class MessageService {
    private final NMDungeonPlugin plugin;
    private YamlConfiguration messages;

    public MessageService(NMDungeonPlugin plugin) {
        this.plugin = plugin;
        File file = new File(plugin.getDataFolder(), "message.yml");
        if (!file.exists()) plugin.saveResource("message.yml", false);
        load(file);
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "message.yml");
        load(file);
    }

    private void load(File file) {
        this.messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream resource = plugin.getResource("message.yml")) {
            if (resource != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(resource, StandardCharsets.UTF_8));
                messages.setDefaults(defaults);
                messages.options().copyDefaults(true);
                messages.save(file);
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not merge message.yml defaults: " + exception.getMessage());
        }
        registerConfigDefaults();
    }

    private void registerConfigDefaults() {
        messages.getKeys(true).forEach(key -> {
            if (messages.isString(key)) plugin.getConfig().addDefault("messages.enabled." + key, true);
        });
    }

    public void send(CommandSender recipient, String key, String... replacements) {
        if (!enabled(key)) return;
        recipient.sendMessage(render(key, replacements));
    }

    /**
     * Sends dynamically composed editor/command text through message.yml as well.
     * The wrapper keys let administrators suppress or decorate these messages without
     * bypassing the central message service.
     */
    public void sendText(CommandSender recipient, String wrapperKey, String message) {
        send(recipient, wrapperKey, "message", message);
    }

    public void actionBar(Player player, String key, String... replacements) {
        if (!enabled(key)) return;
        player.sendActionBar(render(key, replacements));
    }

    public String render(String key, String... replacements) {
        String message = messages.getString(key, "&c[message.yml] Missing key: " + key);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            message = message.replace("{" + replacements[index] + "}", replacements[index + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public boolean enabled(String key) {
        return plugin.getConfig().getBoolean("messages.enabled.all", true)
                && plugin.getConfig().getBoolean("messages.enabled." + key, true);
    }
}
