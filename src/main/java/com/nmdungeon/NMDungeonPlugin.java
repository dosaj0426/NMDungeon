package com.nmdungeon;

import com.nmdungeon.command.DungeonCommand;
import com.nmdungeon.dungeon.DungeonService;
import com.nmdungeon.editor.DungeonEditSessionManager;
import com.nmdungeon.editor.DungeonEditorListener;
import com.nmdungeon.editor.DungeonRepository;
import com.nmdungeon.listener.DungeonListener;
import com.nmdungeon.message.MessageService;
import com.nmdungeon.placeholder.NMDungeonPlaceholderExpansion;
import com.nmdungeon.sound.DungeonSoundService;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class NMDungeonPlugin extends JavaPlugin {
    private DungeonService dungeonService;
    private DungeonEditSessionManager editSessionManager;
    private MessageService messages;
    private DungeonSoundService sounds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        applyConfigDefaults();
        messages = new MessageService(this);
        sounds = new DungeonSoundService(this);
        getConfig().options().copyDefaults(true);
        saveConfig();
        DungeonRepository repository = new DungeonRepository(this);
        dungeonService = new DungeonService(this, repository);
        editSessionManager = new DungeonEditSessionManager(this, repository);

        DungeonCommand command = new DungeonCommand(this, dungeonService, editSessionManager);
        PluginCommand rootCommand = Objects.requireNonNull(getCommand("nmdungeon"));
        rootCommand.setExecutor(command);
        rootCommand.setTabCompleter(command);
        getServer().getPluginManager().registerEvents(new DungeonListener(dungeonService, messages), this);
        getServer().getPluginManager().registerEvents(new DungeonEditorListener(this, editSessionManager), this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new NMDungeonPlaceholderExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered: %nmdungeon_<placeholder>%");
        }
        getLogger().info("NMDungeon MVP enabled.");
    }

    @Override
    public void onDisable() {
        if (dungeonService != null) {
            dungeonService.shutdown();
        }
    }

    public MessageService messages() {
        return messages;
    }

    public DungeonService dungeonService() {
        return dungeonService;
    }

    public DungeonSoundService sounds() {
        return sounds;
    }

    public void reloadPluginFiles() {
        reloadConfig();
        applyConfigDefaults();
        messages.reload();
        sounds.reload();
        getConfig().options().copyDefaults(true);
        saveConfig();
        dungeonService.reloadSettings();
        editSessionManager.reloadSettings();
    }

    private void applyConfigDefaults() {
        getConfig().addDefault("max-module-size.x", 64);
        getConfig().addDefault("max-module-size.y", 32);
        getConfig().addDefault("max-module-size.z", 64);
        getConfig().addDefault("generation.max-concurrent", 1);
        getConfig().addDefault("generation-bossbar.enabled", true);
        getConfig().addDefault("generation-bossbar.title", "&e맵 생성중 (&f{percent}%&e)");
        getConfig().addDefault("generation-bossbar.color", "YELLOW");
        getConfig().addDefault("generation-bossbar.style", "SOLID");
        getConfig().addDefault("load-test.max-runs", 8);
        getConfig().addDefault("default-limits.max-lives", 0);
        getConfig().addDefault("default-limits.time-limit-seconds", 0);
        getConfig().addDefault("exploration-map.enabled", true);
        getConfig().addDefault("exploration-map.item-name", "&a탐험 지도");
        getConfig().options().copyDefaults(true);
        saveConfig();
    }
}
