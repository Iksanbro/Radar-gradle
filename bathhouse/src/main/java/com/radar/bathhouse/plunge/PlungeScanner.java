package com.radar.bathhouse.plunge;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Finds the basin behind a plunge thermometer: everything at or below the thermometer's height that is enclosed by
 * plunge walls (and stoves, chillers, the thermometer itself). The top stays open.
 */
public final class PlungeScanner {
    private PlungeScanner() {
    }

    public static PlungeScan scan(Level level, BlockPos thermometer, Direction facing, int maxVolume) {
        PlungeScan first = null;
        for (Direction dir : order(facing)) {
            BlockPos start = thermometer.relative(dir);
            if (!level.isLoaded(start) || !isInterior(level.getBlockState(start))) {
                continue;
            }
            PlungeScan scan = flood(level, thermometer.getY(), start, maxVolume);
            if (scan.valid()) {
                return scan;
            }
            if (first == null) {
                first = scan;
            }
        }
        return first != null ? first : PlungeScan.invalid(Component.translatable("bathhouse.plunge.problem.no_basin"));
    }

    private static Direction[] order(Direction facing) {
        return new Direction[]{facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite()};
    }

    /** Water, shavings, air and benches make up the inside of a basin. */
    public static boolean isInterior(BlockState state) {
        Block block = state.getBlock();
        return state.isAir() || block instanceof SawdustBlock || block instanceof PlungeBenchBlock
                || (state.getFluidState().is(FluidTags.WATER) && state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty());
    }

    public static boolean isMedium(BlockState state) {
        return state.getBlock() instanceof SawdustBlock || state.getFluidState().is(FluidTags.WATER);
    }

    private static PlungeScan flood(Level level, int rimY, BlockPos start, int maxVolume) {
        LongOpenHashSet interior = new LongOpenHashSet();
        EnumMap<PlungeMaterial, Integer> faces = new EnumMap<>(PlungeMaterial.class);
        Map<BlockPos, Integer> stoves = new HashMap<>();
        Set<BlockPos> chillers = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        interior.add(start.asLong());
        queue.add(start);
        int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (next.getY() > rimY || interior.contains(next.asLong())) {
                    continue;
                }
                if (!level.isLoaded(next)) {
                    return PlungeScan.invalid(Component.translatable("bathhouse.plunge.problem.no_basin"));
                }
                BlockState state = level.getBlockState(next);
                Block block = state.getBlock();
                if (isInterior(state)) {
                    interior.add(next.asLong());
                    if (interior.size() > maxVolume) {
                        return PlungeScan.invalid(Component.translatable("bathhouse.plunge.problem.too_big", maxVolume));
                    }
                    queue.add(next);
                    minX = Math.min(minX, next.getX());
                    minY = Math.min(minY, next.getY());
                    minZ = Math.min(minZ, next.getZ());
                    maxX = Math.max(maxX, next.getX());
                    maxY = Math.max(maxY, next.getY());
                    maxZ = Math.max(maxZ, next.getZ());
                } else if (block instanceof PlungeWallBlock wall) {
                    faces.merge(wall.material(), 1, Integer::sum);
                } else if (block instanceof PlungeThermometerBlock) {
                    faces.merge(PlungeMaterial.WOOD, 1, Integer::sum);
                } else if (block instanceof PlungeStoveBlock) {
                    stoves.merge(next.immutable(), 1, Integer::sum);
                } else if (block instanceof PlungeChillerBlock) {
                    chillers.add(next.immutable());
                } else {
                    return PlungeScan.invalid(Component.translatable("bathhouse.plunge.problem.gap",
                            next.getX(), next.getY(), next.getZ(), block.getName()));
                }
            }
        }

        int water = 0;
        int sawdust = 0;
        int benches = 0;
        int surface = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (long packed : interior) {
            cursor.set(packed);
            BlockState state = level.getBlockState(cursor);
            if (state.getBlock() instanceof PlungeBenchBlock) {
                benches++;
            }
            if (!isMedium(state)) {
                continue;
            }
            if (state.getBlock() instanceof SawdustBlock) {
                sawdust++;
            } else {
                water++;
            }
            if (!isMedium(level.getBlockState(cursor.above()))) {
                surface++;
            }
        }
        AABB bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        return new PlungeScan(true, null, interior, water, sawdust, benches, surface, faces, stoves, chillers, bounds);
    }
}
