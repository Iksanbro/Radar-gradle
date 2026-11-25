package com.radar.bathhouse.climate;

import com.radar.bathhouse.block.ApplianceBlock;
import com.radar.bathhouse.block.BathGaugeBlock;
import com.radar.bathhouse.block.ChimneyBlock;
import com.radar.bathhouse.block.SmokeVentBlock;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Flood-fills the air of a room starting at the gauge. Anything with a collision shape is a wall,
 * except open doors, trapdoors and fence gates, through which the fill escapes.
 */
public final class RoomScanner {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int MAX_FLUE_LENGTH = 24;

    private RoomScanner() {
    }

    public static RoomScan scan(Level level, BlockPos start, int maxVolume) {
        LongOpenHashSet interior = new LongOpenHashSet();
        EnumMap<WallMaterial, Integer> faces = new EnumMap<>(WallMaterial.class);
        Map<BlockPos, Set<Direction>> appliances = new HashMap<>();
        Set<BlockPos> openVents = new HashSet<>();
        Set<BlockPos> closedVents = new HashSet<>();
        Set<BlockPos> draftFlues = new HashSet<>();
        boolean duplicateGauge = false;

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        interior.add(start.asLong());
        queue.add(start);
        int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : DIRECTIONS) {
                BlockPos next = current.relative(dir);
                if (interior.contains(next.asLong())) {
                    continue;
                }
                if (!level.isLoaded(next) || level.isOutsideBuildHeight(next)) {
                    return leaky(interior);
                }
                BlockState state = level.getBlockState(next);
                if (isPassable(level, next, state)) {
                    interior.add(next.asLong());
                    if (interior.size() > maxVolume) {
                        return leaky(interior);
                    }
                    if (state.getBlock() instanceof BathGaugeBlock && next.asLong() < start.asLong()) {
                        duplicateGauge = true;
                    }
                    queue.add(next);
                    minX = Math.min(minX, next.getX());
                    minY = Math.min(minY, next.getY());
                    minZ = Math.min(minZ, next.getZ());
                    maxX = Math.max(maxX, next.getX());
                    maxY = Math.max(maxY, next.getY());
                    maxZ = Math.max(maxZ, next.getZ());
                    continue;
                }

                Block block = state.getBlock();
                if (block instanceof ApplianceBlock) {
                    appliances.computeIfAbsent(next.immutable(), p -> EnumSet.noneOf(Direction.class)).add(dir.getOpposite());
                } else if (block instanceof SmokeVentBlock) {
                    if (state.getValue(SmokeVentBlock.OPEN)) {
                        openVents.add(next.immutable());
                    } else {
                        closedVents.add(next.immutable());
                    }
                } else if (block instanceof ChimneyBlock && !sitsOnAppliance(level, next)) {
                    // A flue opening in the ceiling or wall: the smoke of a chimney-less stove goes through the room into it.
                    draftFlues.add(next.immutable());
                } else {
                    faces.merge(WallMaterial.of(state), 1, Integer::sum);
                }
            }
        }

        AABB bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        return new RoomScan(true, interior, faces, appliances, openVents, closedVents, draftFlues, duplicateGauge, bounds);
    }

    /** Whether this chimney block is part of a stack standing directly on a stove (a sealed flue). */
    private static boolean sitsOnAppliance(Level level, BlockPos chimney) {
        BlockPos.MutableBlockPos cursor = chimney.mutable();
        for (int i = 0; i < MAX_FLUE_LENGTH; i++) {
            cursor.move(Direction.DOWN);
            BlockState below = level.getBlockState(cursor);
            if (ChimneyBlock.isChimney(below)) {
                continue;
            }
            return below.getBlock() instanceof ApplianceBlock;
        }
        return false;
    }

    private static RoomScan leaky(LongOpenHashSet interior) {
        return new RoomScan(false, interior, new EnumMap<>(WallMaterial.class), Map.of(), Set.of(), Set.of(), Set.of(), false, null);
    }

    /** Whether air (and heat) passes through this block. */
    public static boolean isPassable(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return true;
        }
        Block block = state.getBlock();
        if (block instanceof DoorBlock) {
            return state.getValue(DoorBlock.OPEN);
        }
        if (block instanceof TrapDoorBlock) {
            return state.getValue(TrapDoorBlock.OPEN);
        }
        if (block instanceof FenceGateBlock) {
            return state.getValue(FenceGateBlock.OPEN);
        }
        if (block instanceof BathGaugeBlock) {
            return true;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }
}
