package com.radar.bathhouse.stove;

import com.radar.bathhouse.block.ChimneyBlock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * A stove is a column built around its firebox (the core):
 * <pre>
 *   chimney / damper / water tank   ← flue, any order, up to 24 blocks
 *   closed heater | open heater | boiler   ← chamber (optional)
 *   FIREBOX (steel, cast iron, brick or heating element) → fuel tunnel in front of its door (optional)
 *   ash pit                         ← for wood fireboxes
 * </pre>
 * Soapstone cladding may stand beside the firebox and the chamber (up to 8 blocks).
 */
public final class StoveStructure {
    public static final int MAX_FLUE = 24;
    public static final int MAX_CLADDING = 8;

    private StoveStructure() {
    }

    public record Assembly(BlockPos core, FireboxType firebox, Direction facing, @Nullable BlockPos ashPit,
                           ChamberType chamber, @Nullable BlockPos chamberPos, List<BlockPos> flue, boolean chimney,
                           @Nullable BlockPos damper, boolean damperOpen, List<BlockPos> tanks,
                           @Nullable BlockPos tunnel, int cladding) {
    }

    @Nullable
    public static StovePartType partType(BlockState state) {
        return state.getBlock() instanceof StovePartBlock part ? part.type() : null;
    }

    public static boolean isFlue(BlockState state) {
        StovePartType type = partType(state);
        return state.getBlock() instanceof ChimneyBlock || (type != null && type.slot() == StovePartType.Slot.FLUE);
    }

    public static boolean isFirebox(BlockState state) {
        return state.getBlock() instanceof FireboxBlock;
    }

    /** The firebox a block belongs to, or null if it is not part of a stove. */
    @Nullable
    public static BlockPos findCore(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isFirebox(state)) {
            return pos;
        }
        if (state.getBlock() instanceof ChimneyBlock) {
            return coreBelowFlue(level, pos);
        }
        StovePartType type = partType(state);
        if (type == null) {
            return null;
        }
        return switch (type.slot()) {
            case BELOW -> isFirebox(level.getBlockState(pos.above())) ? pos.above() : null;
            case CHAMBER -> isFirebox(level.getBlockState(pos.below())) ? pos.below() : null;
            case FLUE -> coreBelowFlue(level, pos);
            case FRONT -> {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbour = pos.relative(dir);
                    BlockState n = level.getBlockState(neighbour);
                    if (isFirebox(n) && n.getValue(FireboxBlock.FACING) == dir.getOpposite()) {
                        yield neighbour;
                    }
                }
                yield null;
            }
            case SIDE -> {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbour = pos.relative(dir);
                    BlockState n = level.getBlockState(neighbour);
                    if (isFirebox(n)) {
                        yield neighbour;
                    }
                    StovePartType nType = partType(n);
                    if (nType != null && nType.slot() == StovePartType.Slot.CHAMBER && isFirebox(level.getBlockState(neighbour.below()))) {
                        yield neighbour.below();
                    }
                }
                yield null;
            }
        };
    }

    @Nullable
    private static BlockPos coreBelowFlue(BlockGetter level, BlockPos flue) {
        BlockPos.MutableBlockPos cursor = flue.mutable();
        for (int i = 0; i < MAX_FLUE; i++) {
            cursor.move(Direction.DOWN);
            BlockState below = level.getBlockState(cursor);
            if (isFlue(below)) {
                continue;
            }
            if (isFirebox(below)) {
                return cursor.immutable();
            }
            StovePartType type = partType(below);
            if (type != null && type.slot() == StovePartType.Slot.CHAMBER && isFirebox(level.getBlockState(cursor.below()))) {
                return cursor.below().immutable();
            }
            return null;
        }
        return null;
    }

    public static Assembly assemble(BlockGetter level, BlockPos core) {
        BlockState coreState = level.getBlockState(core);
        FireboxType firebox = coreState.getBlock() instanceof FireboxBlock block ? block.type() : FireboxType.STEEL;
        Direction facing = coreState.hasProperty(FireboxBlock.FACING) ? coreState.getValue(FireboxBlock.FACING) : Direction.NORTH;

        BlockPos ashPit = partType(level.getBlockState(core.below())) == StovePartType.ASH_PIT ? core.below() : null;

        ChamberType chamber = ChamberType.NONE;
        BlockPos chamberPos = null;
        StovePartType above = partType(level.getBlockState(core.above()));
        if (above != null && above.slot() == StovePartType.Slot.CHAMBER) {
            chamber = above.chamber();
            chamberPos = core.above();
        }

        List<BlockPos> flue = new ArrayList<>();
        List<BlockPos> tanks = new ArrayList<>();
        boolean chimney = false;
        BlockPos damper = null;
        boolean damperOpen = true;
        BlockPos.MutableBlockPos cursor = (chamberPos != null ? chamberPos : core).mutable();
        for (int i = 0; i < MAX_FLUE; i++) {
            cursor.move(Direction.UP);
            BlockState state = level.getBlockState(cursor);
            if (!isFlue(state)) {
                break;
            }
            BlockPos pos = cursor.immutable();
            flue.add(pos);
            StovePartType type = partType(state);
            if (state.getBlock() instanceof ChimneyBlock) {
                chimney = true;
            } else if (type == StovePartType.DAMPER && damper == null) {
                damper = pos;
                damperOpen = state.getValue(StovePartBlock.ACTIVE);
            } else if (type == StovePartType.WATER_TANK) {
                tanks.add(pos);
            }
        }

        BlockPos front = core.relative(facing);
        BlockPos tunnel = partType(level.getBlockState(front)) == StovePartType.FUEL_TUNNEL ? front : null;

        int cladding = 0;
        for (BlockPos base : chamberPos != null ? List.of(core, chamberPos) : List.of(core)) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (partType(level.getBlockState(base.relative(dir))) == StovePartType.CLADDING) {
                    cladding++;
                }
            }
        }
        return new Assembly(core, firebox, facing, ashPit, chamber, chamberPos, List.copyOf(flue), chimney, damper,
                damperOpen, List.copyOf(tanks), tunnel, Math.min(cladding, MAX_CLADDING));
    }
}
