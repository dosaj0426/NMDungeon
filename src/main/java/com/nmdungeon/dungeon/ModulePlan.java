package com.nmdungeon.dungeon;

import com.nmdungeon.editor.BossDefinition;
import com.nmdungeon.editor.SpawnPointDefinition;
import com.nmdungeon.editor.WaveDefinition;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

public record ModulePlan(int index, ModuleType type, Bounds bounds, Direction entrance, List<Direction> exits,
                         List<BlockPosition> explicitEntranceDoorBlocks, List<BlockPosition> explicitExitDoorBlocks,
                         List<BlockPosition> sealedConnectorBlocks, String sourceModuleId, int rotation, BlockPosition pasteOrigin,
                         List<WaveDefinition> waves, BossDefinition boss, List<SpawnPointDefinition> spawnPoints,
                         List<List<BlockPosition>> doorGroups, PlayerEntryPoint playerEntryPoint, PlayerEntryPoint checkpointPoint,
                         List<ItemStack> clearRewards, List<Double> clearRewardChances, List<String> clearCommands) {
    public ModulePlan(int index, ModuleType type, Bounds bounds, Direction entrance, List<Direction> exits) {
        this(index, type, bounds, entrance, exits, List.of(), List.of(), List.of(), null, 0,
                new BlockPosition(bounds.minX(), bounds.minY(), bounds.minZ()), List.of(), null, List.of(), List.of());
    }

    public ModulePlan(int index, ModuleType type, Bounds bounds, Direction entrance, List<Direction> exits,
                      List<BlockPosition> explicitEntranceDoorBlocks, List<BlockPosition> explicitExitDoorBlocks,
                      List<BlockPosition> sealedConnectorBlocks, String sourceModuleId, int rotation, BlockPosition pasteOrigin,
                      List<WaveDefinition> waves, BossDefinition boss, List<SpawnPointDefinition> spawnPoints,
                      List<List<BlockPosition>> doorGroups) {
        this(index, type, bounds, entrance, exits, explicitEntranceDoorBlocks, explicitExitDoorBlocks, sealedConnectorBlocks,
                sourceModuleId, rotation, pasteOrigin, waves, boss, spawnPoints, doorGroups, null, null, List.of(), List.of(), List.of());
    }

    public enum Direction {
        NORTH, EAST, SOUTH, WEST
    }

    public List<BlockPosition> exitDoorBlocks() {
        if (!explicitExitDoorBlocks.isEmpty()) {
            return explicitExitDoorBlocks;
        }
        List<BlockPosition> positions = new ArrayList<>();
        for (Direction direction : exits) {
            positions.addAll(doorwayBlocks(direction));
        }
        return positions;
    }

    public List<BlockPosition> entranceDoorwayBlocks() {
        if (!explicitEntranceDoorBlocks.isEmpty()) {
            return explicitEntranceDoorBlocks;
        }
        return entrance == null ? List.of() : doorwayBlocks(entrance);
    }

    public boolean usesSchematic() {
        return sourceModuleId != null;
    }

    public BlockPosition toWorldPosition(BlockPosition local) {
        BlockPosition rotated = switch (rotation) {
            case 0 -> local;
            case 90 -> new BlockPosition(local.z(), local.y(), -local.x());
            case 180 -> new BlockPosition(-local.x(), local.y(), -local.z());
            case 270 -> new BlockPosition(-local.z(), local.y(), local.x());
            default -> throw new IllegalStateException("지원하지 않는 모듈 회전값: " + rotation);
        };
        return new BlockPosition(pasteOrigin.x() + rotated.x(), pasteOrigin.y() + rotated.y(), pasteOrigin.z() + rotated.z());
    }

    private List<BlockPosition> doorwayBlocks(Direction direction) {
        List<BlockPosition> positions = new ArrayList<>();
        int centerX = (bounds.minX() + bounds.maxX()) / 2;
        int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
        for (int y = bounds.minY() + 1; y <= bounds.minY() + 4; y++) {
            for (int offset = -2; offset <= 2; offset++) {
                positions.add(switch (direction) {
                    case NORTH -> new BlockPosition(centerX + offset, y, bounds.minZ());
                    case EAST -> new BlockPosition(bounds.maxX(), y, centerZ + offset);
                    case SOUTH -> new BlockPosition(centerX + offset, y, bounds.maxZ());
                    case WEST -> new BlockPosition(bounds.minX(), y, centerZ + offset);
                });
            }
        }
        return positions;
    }
}
