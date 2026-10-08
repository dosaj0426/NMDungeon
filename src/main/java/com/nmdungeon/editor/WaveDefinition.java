package com.nmdungeon.editor;

import java.util.ArrayList;
import java.util.List;

public final class WaveDefinition {
    private final String id;
    private final List<WaveSpawnDefinition> spawns = new ArrayList<>();

    public WaveDefinition(String id, List<WaveSpawnDefinition> spawns) {
        this.id = id;
        this.spawns.addAll(spawns);
    }

    public String id() { return id; }
    public List<WaveSpawnDefinition> spawns() { return spawns; }
    public void addSpawn(WaveSpawnDefinition spawn) { spawns.add(spawn); }
    public boolean removeSpawn(int index) {
        if (index < 0 || index >= spawns.size()) return false;
        spawns.remove(index);
        return true;
    }
    public boolean adjustSpawn(int index, int amountDelta, double levelDelta) {
        if (index < 0 || index >= spawns.size()) return false;
        WaveSpawnDefinition current = spawns.get(index);
        int amount = Math.max(1, Math.min(100, current.amount() + amountDelta));
        double level = Math.max(0.1D, current.level() + levelDelta);
        spawns.set(index, new WaveSpawnDefinition(current.spawnPointId(), current.mythicMobId(), amount, level));
        return true;
    }
}
