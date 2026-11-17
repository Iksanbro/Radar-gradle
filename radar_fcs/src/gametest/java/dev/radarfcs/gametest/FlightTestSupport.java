package dev.radarfcs.gametest;

import ace.flight.block.FlightControlComputerBlockEntity;
import ace.flight.block.ForwardIndicatorBlockEntity;
import dev.radarfcs.compat.flight.FlightBridge;
import dev.radarfcs.compat.flight.FlightBridgeBlockEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Классы Create: Flight Control для игровых тестов моста (грузится только при установленном FC). */
final class FlightTestSupport {
    private FlightTestSupport() {}

    static BlockState bridge(Direction facing) {
        return FlightBridge.BLOCK.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
    }

    /** Индикатор направления вперёд Flight Control: «вперёд» корабля - forward (модель смотрит наоборот). */
    static BlockState indicator(Direction forward) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create_flight_control", "forward_indicator"))
                .defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, forward.getOpposite());
    }

    static BlockState computer() {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create_flight_control", "flight_control_computer"))
                .defaultBlockState();
    }

    static boolean isBridge(Object be) {
        return be instanceof FlightBridgeBlockEntity;
    }

    /** Момент и сила моста на этом шаге: {Fx, Fy, Fz, Tx, Ty, Tz} в осях корабля. */
    static double[] wrench(Object bridge, Object sub) {
        double[] f = new double[3], t = new double[3];
        ((FlightBridgeBlockEntity) bridge).contributeWrench(sub, RigidBodyHandle.of((ServerSubLevel) sub), 0.05, f, t);
        return new double[]{f[0], f[1], f[2], t[0], t[1], t[2]};
    }

    /** Связать мост с индикатором в точке indicator (как инструментальный пистолет). */
    static boolean linkIndicator(Level level, Object bridge, BlockPos indicator) {
        return level.getBlockEntity(indicator) instanceof ForwardIndicatorBlockEntity ind
                && ((FlightBridgeBlockEntity) bridge).linkForwardIndicator(ind);
    }

    static boolean contributes(Object bridge) {
        return ((FlightBridgeBlockEntity) bridge).isContributing();
    }

    static String forward(Object bridge) {
        return ((FlightBridgeBlockEntity) bridge).forwardFacing().serializedName();
    }

    /** Связать мост с компьютером управления полётом (как пистолет: мост, затем компьютер). */
    static boolean linkComputer(Level level, Object bridge, BlockPos computer) {
        FlightBridgeBlockEntity b = (FlightBridgeBlockEntity) bridge;
        if (!(level.getBlockEntity(computer) instanceof FlightControlComputerBlockEntity cc)) return false;
        cc.addController(b.getBlockPos());
        b.setLinkedComputer(b.getBlockPos(), computer);
        return cc.hasReciprocalControllerAt(b.getBlockPos()) && b.isContributing();
    }

    /** Мост убран - компьютер забыл его. */
    static boolean computerForgot(Level level, BlockPos computer, BlockPos bridge) {
        return level.getBlockEntity(computer) instanceof FlightControlComputerBlockEntity cc && !cc.hasLinkedControllerAt(bridge);
    }
}
