package dev.aeroschematics.place;

import com.simibubi.create.content.schematics.SchematicPrinter;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public final class SchematicPrinterUtil {
    private SchematicPrinterUtil() {
    }

    /** Blocks that need a supporting block first (torches, levers, gantry carriages, arms...), as in Create. */
    static boolean isDeferred(BlockState state) {
        return SchematicPrinter.shouldDeferBlock(state);
    }

    static boolean isSolidSeed(SchematicLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        try {
            return state.isCollisionShapeFullBlock(level, pos) && !state.hasBlockEntity();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Second halves that are created when the first half is placed (upper door half, bed head, piston head). Same rule
     * as the schematicannon's {@code shouldIgnoreBlockState}, except belts: inside a physics structure every belt
     * segment is printed individually (middle segments are free, as in Create's own requirement).
     */
    public static boolean isImplicitHalf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return true;
        }
        return state.getBlock() instanceof PistonHeadBlock;
    }
}
