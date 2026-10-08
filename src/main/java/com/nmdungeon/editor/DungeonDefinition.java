package com.nmdungeon.editor;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

public final class DungeonDefinition {
    private final String id;
    private final Map<String, ModuleDefinition> modules = new LinkedHashMap<>();
    private int minCorridors = 1;
    private int maxCorridors = 2;
    private int minRooms = 1;
    private int maxRooms = 2;
    private int maxBranches = 5;
    /** When enabled, regular combat rooms can only connect through corridor modules. */
    private boolean enforceCombatRoomCorridors;
    /** When enabled, a boss-room door stays closed until every non-terminal module is cleared. */
    private boolean requireAllModulesClearedForBoss;
    /** 0 means unlimited shared party lives. */
    private int maxLives;
    /** 0 means no dungeon time limit. */
    private int timeLimitSeconds;
    private final List<ItemStack> clearRewards = new ArrayList<>();
    private final List<Double> clearRewardChances = new ArrayList<>();

    public DungeonDefinition(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public Map<String, ModuleDefinition> modules() {
        return modules;
    }

    public void addModule(ModuleDefinition module) {
        modules.put(module.id(), module);
    }

    public int minCorridors() { return minCorridors; }
    public int maxCorridors() { return maxCorridors; }
    public int minRooms() { return minRooms; }
    public int maxRooms() { return maxRooms; }
    public int maxBranches() { return maxBranches; }
    public boolean enforceCombatRoomCorridors() { return enforceCombatRoomCorridors; }
    public boolean requireAllModulesClearedForBoss() { return requireAllModulesClearedForBoss; }
    public int maxLives() { return maxLives; }
    public int timeLimitSeconds() { return timeLimitSeconds; }
    public List<ItemStack> clearRewards() { return cloneItems(clearRewards); }
    public List<Double> clearRewardChances() { return List.copyOf(clearRewardChances); }
    public double clearRewardChance(int index) { return index < 0 || index >= clearRewardChances.size() ? 100.0D : clearRewardChances.get(index); }

    public void setGenerationRules(int minCorridors, int maxCorridors, int minRooms, int maxRooms, int maxBranches) {
        this.minCorridors = minCorridors;
        this.maxCorridors = maxCorridors;
        this.minRooms = minRooms;
        this.maxRooms = maxRooms;
        this.maxBranches = maxBranches;
    }

    public void setLimits(int maxLives, int timeLimitSeconds) {
        this.maxLives = Math.max(0, maxLives);
        this.timeLimitSeconds = Math.max(0, timeLimitSeconds);
    }

    public void setEnforceCombatRoomCorridors(boolean enforceCombatRoomCorridors) {
        this.enforceCombatRoomCorridors = enforceCombatRoomCorridors;
    }

    public boolean toggleCombatRoomCorridorRule() {
        enforceCombatRoomCorridors = !enforceCombatRoomCorridors;
        return enforceCombatRoomCorridors;
    }

    public void setRequireAllModulesClearedForBoss(boolean requireAllModulesClearedForBoss) {
        this.requireAllModulesClearedForBoss = requireAllModulesClearedForBoss;
    }

    public boolean toggleRequireAllModulesClearedForBoss() {
        requireAllModulesClearedForBoss = !requireAllModulesClearedForBoss;
        return requireAllModulesClearedForBoss;
    }

    public void addClearReward(ItemStack item) { if (item != null && !item.getType().isAir()) { clearRewards.add(item.clone()); clearRewardChances.add(100.0D); } }
    public boolean removeClearReward(int index) {
        if (index < 0 || index >= clearRewards.size()) return false;
        clearRewards.remove(index); clearRewardChances.remove(index); return true;
    }
    public void setClearRewards(List<ItemStack> items) { setClearRewards(items, List.of()); }
    public void setClearRewards(List<ItemStack> items, List<Double> chances) {
        clearRewards.clear(); clearRewardChances.clear();
        for (int index = 0; index < items.size(); index++) {
            ItemStack item = items.get(index);
            if (item == null || item.getType().isAir()) continue;
            clearRewards.add(item.clone());
            clearRewardChances.add(clampChance(index < chances.size() ? chances.get(index) : 100.0D));
        }
    }
    public void setClearRewardChance(int index, double chance) {
        if (index >= 0 && index < clearRewardChances.size()) clearRewardChances.set(index, clampChance(chance));
    }

    private static double clampChance(double chance) { return Math.max(0.0D, Math.min(100.0D, chance)); }

    private static List<ItemStack> cloneItems(List<ItemStack> items) {
        return items.stream().map(ItemStack::clone).toList();
    }

    public boolean setGenerationRule(String key, int value) {
        switch (key.toLowerCase()) {
            case "min-corridors" -> minCorridors = value;
            case "max-corridors" -> maxCorridors = value;
            case "min-rooms" -> minRooms = value;
            case "max-rooms" -> maxRooms = value;
            case "max-branches" -> maxBranches = value;
            case "max-lives", "lives" -> maxLives = value;
            case "time-limit-seconds", "time" -> timeLimitSeconds = value;
            default -> { return false; }
        }
        return true;
    }
}
