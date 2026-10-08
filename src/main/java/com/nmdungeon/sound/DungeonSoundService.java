package com.nmdungeon.sound;

import com.nmdungeon.NMDungeonPlugin;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Loads the optional, gameplay-only sounds independently from config.yml. */
public final class DungeonSoundService {
    private final NMDungeonPlugin plugin;
    private YamlConfiguration sounds;

    public DungeonSoundService(NMDungeonPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "sound.yml");
        if (!file.exists()) plugin.saveResource("sound.yml", false);
        sounds = YamlConfiguration.loadConfiguration(file);
        try (InputStream resource = plugin.getResource("sound.yml")) {
            if (resource == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(resource, StandardCharsets.UTF_8));
            sounds.setDefaults(defaults);
            sounds.options().copyDefaults(true);
            sounds.save(file);
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not merge sound.yml defaults: " + exception.getMessage());
        }
    }

    public void playDoorOpening(Collection<Player> players, Location location) {
        play(players, location, "door-opening.start");
    }

    public void playDoorOpened(Collection<Player> players, Location location) {
        play(players, location, "door-opening.complete");
    }

    private void play(Collection<Player> players, Location location, String path) {
        if (!sounds.getBoolean(path + ".enabled", true)) return;
        String configured = sounds.getString(path + ".sound", "BLOCK_IRON_DOOR_OPEN");
        Sound sound;
        try {
            sound = Sound.valueOf(configured.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            plugin.getLogger().warning("Invalid NMDungeon sound at " + path + ".sound: " + configured);
            return;
        }
        float volume = (float) Math.max(0.0D, sounds.getDouble(path + ".volume", 1.0D));
        float pitch = (float) Math.max(0.0D, sounds.getDouble(path + ".pitch", 1.0D));
        players.stream().filter(Player::isOnline).forEach(player -> player.playSound(location, sound, volume, pitch));
    }
}
