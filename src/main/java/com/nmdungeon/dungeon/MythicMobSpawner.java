package com.nmdungeon.dungeon;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;

/** Optional MythicMobs integration without a build-time dependency on its server JAR. */
public final class MythicMobSpawner {
    public Optional<UUID> spawn(String mythicMobId, Location location, double level) {
        try {
            Class<?> mythicBukkit = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object instance = mythicBukkit.getMethod("inst").invoke(null);
            Object manager = mythicBukkit.getMethod("getMobManager").invoke(instance);
            Object mobOptional = manager.getClass().getMethod("getMythicMob", String.class).invoke(manager, mythicMobId);
            if (!(mobOptional instanceof Optional<?> optional) || optional.isEmpty()) return Optional.empty();

            Class<?> adapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter");
            Object abstractLocation = adapter.getMethod("adapt", Location.class).invoke(null, location);
            Object mob = optional.get();
            Method spawn = java.util.Arrays.stream(mob.getClass().getMethods())
                    .filter(method -> method.getName().equals("spawn") && method.getParameterCount() == 2
                            && method.getParameterTypes()[1] == double.class)
                    .findFirst().orElseThrow();
            Object activeMob = spawn.invoke(mob, abstractLocation, level);
            Object id = activeMob.getClass().getMethod("getUniqueId").invoke(activeMob);
            return id instanceof UUID uuid ? Optional.of(uuid) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return Optional.empty();
        }
    }
}
