package com.nmdungeon.dungeon;

public enum ModuleType {
    ENTRANCE(false),
    CORRIDOR(false),
    ROOM(true),
    BOSS(true),
    EXIT(false);

    private final boolean requiresClearCondition;

    ModuleType(boolean requiresClearCondition) {
        this.requiresClearCondition = requiresClearCondition;
    }

    public boolean requiresClearCondition() {
        return requiresClearCondition;
    }
}
