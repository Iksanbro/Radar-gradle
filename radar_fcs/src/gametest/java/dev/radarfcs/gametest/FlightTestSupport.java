package dev.radarfcs.gametest;

import ace.flight.block.FlightControlComputerBlockEntity;
import ace.flight.block.ForwardIndicatorBlockEntity;
import dev.radarfcs.compat.flight.FlightBridge;
import dev.radarfcs.compat.flight.FlightBridgeBlockEntity;
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

    /** Блок Flight Control по имени (top=вверх, если есть). */
    static BlockState fc(String id) {
        BlockState st = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create_flight_control", id)).defaultBlockState();
        for (var prop : st.getProperties()) {
            if (prop.getName().equals("top") && prop instanceof net.minecraft.world.level.block.state.properties.DirectionProperty dp) {
                st = st.setValue(dp, Direction.UP);
            }
        }
        return st;
    }

    /** Включить компьютер управления полётом: рабочий режим, тяга применяется (по умолчанию он выключен и в предпросмотре). */
    static void startComputer(Level level, BlockPos computer) {
        if (level.getBlockEntity(computer) instanceof FlightControlComputerBlockEntity cc) {
            cc.setWorking(true);
            cc.workDryRun = false;
        }
    }

    /** Связать регулятор с компьютером (как пистолет: регулятор, затем компьютер). */
    static boolean linkController(Level level, BlockPos computer, BlockPos controller) {
        if (!(level.getBlockEntity(computer) instanceof FlightControlComputerBlockEntity cc)
                || !(level.getBlockEntity(controller) instanceof ace.flight.api.WrenchContributor wc)) return false;
        cc.addController(controller);
        wc.setLinkedComputer(controller, computer);
        return cc.hasReciprocalControllerAt(controller);
    }

    /** Стабилизатор ориентации: включён, держит текущую ориентацию (и курс) - мешал бы повороту к цели. */
    static void attitudeHoldCurrent(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ace.flight.block.AttitudeHoldBlockEntity a) {
            a.setConfig(a.kp, a.kd, ace.flight.control.AttitudeHoldMode.HOLD_CURRENT, a.getBodyForwardFacing(), true);
        }
    }

    /**
     * Команды аренды моста у компьютера: регулятор и ось -> цель (TRACK) или «участие» (REQUEST_ON/OFF). Ключи - вид
     * регулятора и ось, например «BODY_VELOCITY:BODY_VELOCITY_X», «ATTITUDE_HOLD:participation».
     */
    static java.util.Map<String, Object> commands(Level level, BlockPos computer, BlockPos bridge) {
        java.util.Map<String, Object> out = new java.util.TreeMap<>();
        if (!(level.getBlockEntity(computer) instanceof FlightControlComputerBlockEntity cc)) return out;
        java.util.Map<Integer, String> kinds = new java.util.HashMap<>();
        for (var n : cc.snapshotControlNetwork(bridge, level.getGameTime()).controllers()) kinds.put(n.index(), n.kind().name());
        try {
            var frame = FlightBridgeBlockEntity.broker(cc).effectiveFrame();
            for (var e : frame.commands().entrySet()) {
                if (e.getValue().identity().terminalPosition() != bridge.asLong()) continue;
                String kind = kinds.getOrDefault(e.getKey().controllerIndex(), "?");
                if (e.getKey() instanceof ace.flight.navigation.NavigationControlDomain.Axis ax) {
                    out.put(kind + ":" + ax.semanticAxis().name(), e.getValue().command() instanceof ace.flight.navigation.NavigationControlCommand.Axis c
                            ? (Object) c.target() : e.getValue().command().toString());
                } else {
                    out.put(kind + ":participation", e.getValue().command().toString());
                }
            }
        } catch (ReflectiveOperationException ex) {
            out.put("error", ex.toString());
        }
        return out;
    }

    /** Связать мост с индикатором в точке indicator (как инструментальный пистолет). */
    static boolean linkIndicator(Level level, Object bridge, BlockPos indicator) {
        return level.getBlockEntity(indicator) instanceof ForwardIndicatorBlockEntity ind
                && ((FlightBridgeBlockEntity) bridge).linkForwardIndicator(ind);
    }

    static String forward(Object bridge) {
        return ((FlightBridgeBlockEntity) bridge).forwardFacing().serializedName();
    }

    /** Связать мост с компьютером управления полётом (как пистолет: мост, затем компьютер). */
    static boolean linkComputer(Level level, Object bridge, BlockPos computer) {
        return linkController(level, computer, ((FlightBridgeBlockEntity) bridge).getBlockPos());
    }

    /** Мост убран - компьютер забыл его. */
    static boolean computerForgot(Level level, BlockPos computer, BlockPos bridge) {
        return level.getBlockEntity(computer) instanceof FlightControlComputerBlockEntity cc && !cc.hasLinkedControllerAt(bridge);
    }
}
