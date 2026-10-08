package com.nmdungeon.dungeon;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

public record DungeonPlan(List<ModulePlan> modules, Bounds mapBounds, Location startLocation, List<ItemStack> clearRewards,
                          List<Double> clearRewardChances, boolean requireAllModulesClearedForBoss) {
    public DungeonPlan(List<ModulePlan> modules, Bounds mapBounds, Location startLocation) {
        this(modules, mapBounds, startLocation, List.of(), List.of(), false);
    }
}
