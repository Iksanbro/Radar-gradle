package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AutopilotBlockEntity;
import dev.radarfcs.block.RadarMonitorBlock;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.core.Autopilot;
import dev.radarfcs.registry.FcsRegistry;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Мост автопилота Create: Flight Control (без Flight Control и Sable тесты пропускаются). */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class FlightGameTests {
    private static final String ARENA = "arena";

    @BeforeBatch(batch = "flight") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "flight") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * Мост на постройке Sable (лицом на север), монитор на земле в той же радарной сети назначает хаска к востоку.
     * Мост ведёт эту цель: без индикатора нос - лицевая сторона (север), цель справа - момент по курсу вправо
     * (вокруг -Y), сила к цели (+X; держать 10 блоков, до цели ~22). Индикатор «вперёд = восток» - нос уже на цели,
     * момента по курсу почти нет. Режим «дальность и высота» с высотой +12 над целью - сила вверх. Связь с компьютером
     * управления полётом - мост участвует; сигнал редстоуна - пауза, цели нет; мост убрали - компьютер его забыл.
     */
    @GameTest(template = ARENA, batch = "flight", timeoutTicks = 400)
    public static void bridgeSteersToDesignatedTarget(GameTestHelper h) {
        if (!ModList.get().isLoaded("create_flight_control") || !ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        UUID net = UUID.randomUUID();
        // постройка: платформа 3x3 на высоте 4, на ней мост (лицом на север), индикатор (вперёд - восток) и компьютер
        for (int x = 10; x <= 12; x++) for (int z = 30; z <= 32; z++) h.setBlock(new BlockPos(x, 4, z), Blocks.IRON_BLOCK.defaultBlockState());
        BlockPos bridgeRel = new BlockPos(11, 5, 31);
        h.setBlock(bridgeRel, FlightTestSupport.bridge(Direction.NORTH));
        h.setBlock(new BlockPos(10, 5, 30), FlightTestSupport.indicator(Direction.EAST));
        h.setBlock(new BlockPos(12, 5, 32), FlightTestSupport.computer());
        FcsGameTests.placeLink(h, bridgeRel, Direction.UP, net);
        Object sub = SableTestSupport.staticSub(h, new BlockPos(10, 4, 30), new BlockPos(12, 6, 32));
        // монитор 2x2 на земле в той же сети
        BlockState mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH);
        for (int x = 2; x <= 3; x++) for (int y = 2; y <= 3; y++) {
            h.setBlock(new BlockPos(x, y, 44), mon);
            FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(x, y, 44)));
        }
        FcsGameTests.placeLink(h, new BlockPos(3, 3, 44), Direction.UP, net);
        Mob husk = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(33.5, 1, 31.5), 0, 0));
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        Object[] bridge = new Object[1];
        double[] turn = {0};                                               // момент по курсу с носом на север
        BlockPos[] at = new BlockPos[3];                                   // мост, индикатор, компьютер (в плоте)
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            if (bridge[0] == null) {
                for (BlockPos p : dev.radarfcs.link.LinkRegistry.members(level, net)) {
                    if (FlightTestSupport.isBridge(level.getBlockEntity(p))) {
                        bridge[0] = level.getBlockEntity(p);
                        at[0] = p;
                    }
                }
                if (bridge[0] == null) {
                    if (t > 100) h.fail("bridge not found in the radar network");
                    return;
                }
                // индикатор и компьютер - на тех же местах относительно моста
                at[1] = at[0].offset(-1, 0, -1);
                at[2] = at[0].offset(1, 0, 1);
            }
            AutopilotBlockEntity b = (AutopilotBlockEntity) bridge[0];
            switch (phase.get()) {
                case 0 -> {                                                 // назначить цель на мониторе
                    if (!(level.getBlockEntity(h.absolutePos(new BlockPos(2, 2, 44))) instanceof RadarMonitorBlockEntity m)) return;
                    m.master().designate(husk.getUUID(), null);
                    if (husk.getUUID().equals(m.master().localDesignation(level.getGameTime()))) {
                        b.mode = Autopilot.Mode.DISTANCE;
                        b.distance = 10;
                        b.heading = true;
                        phase.set(1);
                        since.set(t);
                    } else if (t > 150) {
                        h.fail("monitor did not designate the husk");
                    }
                }
                case 1 -> {                                                 // без индикатора: нос - север
                    if (b.goal() == null) {
                        if (t - since.get() > 60) h.fail("bridge has no goal, state " + b.state());
                        return;
                    }
                    if (!b.targetName().contains("Husk") && !b.targetName().contains("Кадавр")) h.fail("wrong target " + b.targetName());
                    double[] w = FlightTestSupport.wrench(bridge[0], sub);
                    FcsMod.LOG.info("[radar_fcs test] flight_bridge: nose north, target east: F=({}, {}, {}) T=({}, {}, {}), state {}",
                            f(w[0]), f(w[1]), f(w[2]), f(w[3]), f(w[4]), f(w[5]), b.state());
                    if (!"north".equals(FlightTestSupport.forward(bridge[0]))) h.fail("forward without indicator: " + FlightTestSupport.forward(bridge[0]));
                    turn[0] = w[4];
                    if (!(w[4] < 0)) h.fail("expected a right turn (torque about -Y), got " + f(w[4]));
                    if (!(w[0] > 0) || Math.abs(w[2]) > Math.abs(w[0]) * 0.3) h.fail("expected force toward the target (+X), got " + f(w[0]) + ", " + f(w[2]));
                    if (Math.abs(w[1]) > 1e-6) h.fail("distance-only mode must not push vertically: " + f(w[1]));
                    if (b.state() != AutopilotBlockEntity.State.NO_COMPUTER) h.fail("expected NO_COMPUTER, got " + b.state());
                    if (!FlightTestSupport.linkIndicator(level, bridge[0], at[1])) h.fail("indicator link failed");
                    phase.set(2);
                }
                case 2 -> {                                                 // индикатор: нос - восток, на цель
                    if (!"east".equals(FlightTestSupport.forward(bridge[0]))) h.fail("forward with indicator: " + FlightTestSupport.forward(bridge[0]));
                    double[] w = FlightTestSupport.wrench(bridge[0], sub);
                    FcsMod.LOG.info("[radar_fcs test] flight_bridge: nose east (indicator): T=({}, {}, {})", f(w[3]), f(w[4]), f(w[5]));
                    if (Math.abs(w[4]) > 0.05 * Math.abs(turn[0])) h.fail("nose on target, still turning: " + f(w[4]));
                    b.mode = Autopilot.Mode.DISTANCE_ALTITUDE;
                    b.height = 12;
                    phase.set(3);
                    since.set(t);
                }
                case 3 -> {                                                 // дальность и высота: +12 над целью
                    if (t - since.get() < 4) return;                       // настройки уходят в цель на следующем опросе
                    double[] w = FlightTestSupport.wrench(bridge[0], sub);
                    FcsMod.LOG.info("[radar_fcs test] flight_bridge: distance+altitude: F=({}, {}, {})", f(w[0]), f(w[1]), f(w[2]));
                    if (!(w[1] > 0)) h.fail("expected an upward force (ship below target + 12), got " + f(w[1]));
                    if (!FlightTestSupport.linkComputer(level, bridge[0], at[2])) h.fail("computer link failed");
                    phase.set(4);
                    since.set(t);
                }
                case 4 -> {
                    if (t - since.get() < 12) return;
                    if (b.state() != AutopilotBlockEntity.State.ACTIVE) h.fail("expected ACTIVE with a computer, got " + b.state());
                    level.setBlock(at[0].below().east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                    level.setBlock(at[0].east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                    phase.set(5);
                    since.set(t);
                }
                case 5 -> {
                    if (t - since.get() < 12) return;
                    if (b.state() != AutopilotBlockEntity.State.PAUSED || b.goal() != null) h.fail("redstone did not pause: " + b.state());
                    level.removeBlock(at[0], false);
                    phase.set(6);
                }
                case 6 -> {
                    if (!FlightTestSupport.computerForgot(level, at[2], at[0])) h.fail("computer still lists the removed bridge");
                    h.succeed();
                }
                default -> { }
            }
        });
    }

}
