package dev.aeroschematics.gametest;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

/** Fills and measures fluid containers through their real fluid capability, whatever their NBT layout is. */
final class FluidFill {
    private static final Direction[] SIDES = {null, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private FluidFill() {
    }

    static void fill(ServerLevel level, BlockPos pos, FluidStack stack) {
        for (Direction side : SIDES) {
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
            if (handler != null && handler.fill(stack.copy(), IFluidHandler.FluidAction.EXECUTE) > 0) {
                return;
            }
        }
        // Containers that refuse insertion from outside (item drain, hose pulley): fill their tank directly.
        BlockEntity blockEntity = level.getBlockEntity(pos);
        SmartFluidTankBehaviour tanks = blockEntity == null ? null : BlockEntityBehaviour.get(blockEntity, SmartFluidTankBehaviour.TYPE);
        if (tanks != null && tanks.getPrimaryHandler().fill(stack.copy(), IFluidHandler.FluidAction.EXECUTE) > 0) {
            blockEntity.setChanged();
            return;
        }
        throw new GameTestAssertException("setup: could not fill " + level.getBlockState(pos) + " with " + stack.getHoverName().getString());
    }

    /** Total fluid amount visible through the block's fluid capability, or -1 if it has none. */
    static int amount(ServerLevel level, BlockPos pos) {
        int best = -1;
        for (Direction side : SIDES) {
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
            if (handler == null) {
                continue;
            }
            int total = 0;
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                total += handler.getFluidInTank(tank).getAmount();
            }
            best = Math.max(best, total);
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        SmartFluidTankBehaviour tanks = blockEntity == null ? null : BlockEntityBehaviour.get(blockEntity, SmartFluidTankBehaviour.TYPE);
        if (tanks != null) {
            best = Math.max(best, tanks.getPrimaryHandler().getFluidAmount());
        }
        return best;
    }

    @Nullable
    static String describe(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos) + " holds " + amount(level, pos) + " mB";
    }
}
