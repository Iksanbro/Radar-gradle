package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AutopilotBlockEntity;
import dev.radarfcs.block.RadarMonitorBlock;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.core.Autopilot;
import dev.radarfcs.registry.FcsRegistry;
import java.util.Map;
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
    private static final String VEL = "BODY_VELOCITY:", ANG = "BODY_ANGULAR_VELOCITY:";

    @BeforeBatch(batch = "flight") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "flight") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }

    private static double num(Map<String, Object> m, String key) {
        return m.get(key) instanceof Double d ? d : Double.NaN;
    }

    /**
     * Мост на постройке Sable (лицом на север) с компьютером управления полётом, стабилизаторами ориентации (держать
     * текущую) и высоты; монитор на земле в той же радарной сети назначает хаска к востоку. Без регуляторов скорости -
     * «нужны регуляторы». Регуляторы скорости и угловой скорости связали - мост берёт аренду, как навигационный терминал
     * Flight Control: регулятор скорости включён и ведёт к цели (+X), регулятор угловой скорости - поворот вправо (-Y),
     * оба стабилизатора выключены. Индикатор «вперёд = восток» - поворота по курсу почти нет; тангаж - цель ниже, нос
     * вниз (-Z). «Дальность и высота» +12 - вверх. Назначение сняли - других целей (хаск рядом) не ведёт, висит на месте. Редстоун - пауза, аренда
     * отдана. Мост сломан - компьютер его забыл.
     */
    @GameTest(template = ARENA, batch = "flight", timeoutTicks = 500)
    public static void bridgeLeasesFlightControllers(GameTestHelper h) {
        if (!ModList.get().isLoaded("create_flight_control") || !ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        UUID net = UUID.randomUUID();
        for (int x = 10; x <= 12; x++) for (int z = 30; z <= 32; z++) h.setBlock(new BlockPos(x, 4, z), Blocks.IRON_BLOCK.defaultBlockState());
        BlockPos bridgeRel = new BlockPos(11, 5, 31);
        // относительно моста: индикатор, компьютер, регулятор скорости, угловой скорости, стабилизаторы ориентации и высоты
        BlockPos dInd = new BlockPos(-1, 0, -1), dCc = new BlockPos(1, 0, 1), dVel = new BlockPos(-1, 0, 1), dAng = new BlockPos(1, 0, -1),
                dAtt = new BlockPos(-1, 0, 0), dAlt = new BlockPos(1, 0, 0);
        h.setBlock(bridgeRel, FlightTestSupport.bridge(Direction.NORTH));
        h.setBlock(bridgeRel.offset(dInd), FlightTestSupport.indicator(Direction.EAST));
        h.setBlock(bridgeRel.offset(dCc), FlightTestSupport.computer());
        h.setBlock(bridgeRel.offset(dVel), FlightTestSupport.fc("velocity_coupler"));
        h.setBlock(bridgeRel.offset(dAng), FlightTestSupport.fc("angular_velocity_coupler"));
        h.setBlock(bridgeRel.offset(dAtt), FlightTestSupport.fc("attitude_hold"));
        h.setBlock(bridgeRel.offset(dAlt), FlightTestSupport.fc("altitude_hold"));
        FcsGameTests.placeLink(h, bridgeRel, Direction.UP, net);
        ServerLevel lv = h.getLevel();
        BlockPos ab = h.absolutePos(bridgeRel), acc = ab.offset(dCc);
        FlightTestSupport.startComputer(lv, acc);
        for (BlockPos d : new BlockPos[]{dAtt, dAlt}) FlightTestSupport.linkController(lv, acc, ab.offset(d));
        FlightTestSupport.attitudeHoldCurrent(lv, ab.offset(dAtt));
        Object sub = SableTestSupport.staticSub(h, new BlockPos(10, 4, 30), new BlockPos(12, 6, 32));
        BlockState mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH);
        for (int x = 2; x <= 3; x++) for (int y = 2; y <= 3; y++) {
            h.setBlock(new BlockPos(x, y, 44), mon);
            FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(x, y, 44)));
        }
        FcsGameTests.placeLink(h, new BlockPos(3, 3, 44), Direction.UP, net);
        Mob husk = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(33.5, 1, 31.5), 0, 0));
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        Object[] bridge = new Object[1];
        BlockPos[] at = new BlockPos[1];                                  // мост в плоте постройки
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
                if (!FlightTestSupport.linkComputer(level, bridge[0], at[0].offset(dCc))) h.fail("bridge-computer link failed");
            }
            AutopilotBlockEntity b = (AutopilotBlockEntity) bridge[0];
            BlockPos cc = at[0].offset(dCc);
            switch (phase.get()) {
                case 0 -> {                                                 // назначить цель на мониторе
                    if (!(level.getBlockEntity(h.absolutePos(new BlockPos(2, 2, 44))) instanceof RadarMonitorBlockEntity m)) return;
                    m.master().designate(husk.getUUID(), null);
                    if (husk.getUUID().equals(m.master().localDesignation(level.getGameTime()))) {
                        b.mode = Autopilot.Mode.DISTANCE;
                        b.distance = 10;
                        phase.set(1);
                        since.set(t);
                    } else if (t > 150) {
                        h.fail("monitor did not designate the husk");
                    }
                }
                case 1 -> {                                                 // регуляторов скорости нет
                    if (t - since.get() < 30) return;
                    if (b.goal() == null) h.fail("bridge has no goal, state " + b.state());
                    if (b.state() != AutopilotBlockEntity.State.NEED_COUPLERS && b.state() != AutopilotBlockEntity.State.COMPUTER_OFF) {
                        h.fail("expected NEED_COUPLERS without couplers, got " + b.state());
                    }
                    FcsMod.LOG.info("[radar_fcs test] flight_bridge: no couplers - {}", b.state());
                    FlightTestSupport.linkController(level, cc, at[0].offset(dVel));
                    FlightTestSupport.linkController(level, cc, at[0].offset(dAng));
                    phase.set(2);
                    since.set(t);
                }
                case 2 -> {                                                 // аренда: регуляторы включены, стабилизаторы выключены
                    if (t - since.get() < 20) return;
                    Map<String, Object> c = FlightTestSupport.commands(level, cc, at[0]);
                    FcsMod.LOG.info("[radar_fcs test] flight_bridge: nose north, target east - {} {}", b.state(), c);
                    if (b.state() != AutopilotBlockEntity.State.ACTIVE) h.fail("expected ACTIVE with couplers, got " + b.state());
                    if (!"REQUEST_ON".equals(c.get(VEL + "participation")) || !"REQUEST_ON".equals(c.get(ANG + "participation"))) h.fail("couplers not requested on: " + c);
                    if (!"REQUEST_OFF".equals(c.get("ATTITUDE_HOLD:participation"))) h.fail("attitude hold not suppressed: " + c);
                    if (!"REQUEST_OFF".equals(c.get("ALTITUDE_HOLD:participation"))) h.fail("altitude hold not suppressed: " + c);
                    if (!(num(c, VEL + "BODY_VELOCITY_X") > 0)) h.fail("expected velocity toward the target (+X): " + c);
                    if (!(num(c, ANG + "BODY_ANGULAR_VELOCITY_Y") < 0)) h.fail("expected a right turn (-Y): " + c);
                    if (!FlightTestSupport.linkIndicator(level, bridge[0], at[0].offset(dInd))) h.fail("indicator link failed");
                    b.pitch = true;
                    phase.set(3);
                    since.set(t);
                }
                case 3 -> {                                                 // индикатор: нос на восток; тангаж - цель ниже
                    if (t - since.get() < 6) return;
                    Map<String, Object> c = FlightTestSupport.commands(level, cc, at[0]);
                    FcsMod.LOG.info("[radar_fcs test] flight_bridge: nose east (indicator), pitch - {}", c);
                    if (!"east".equals(FlightTestSupport.forward(bridge[0]))) h.fail("forward with indicator: " + FlightTestSupport.forward(bridge[0]));
                    if (Math.abs(num(c, ANG + "BODY_ANGULAR_VELOCITY_Y")) > 0.05) h.fail("nose on target, still turning: " + c);
                    if (!(num(c, ANG + "BODY_ANGULAR_VELOCITY_Z") < 0)) h.fail("expected nose down (-Z): " + c);
                    b.pitch = false;
                    b.mode = Autopilot.Mode.DISTANCE_ALTITUDE;
                    b.height = 12;
                    phase.set(4);
                    since.set(t);
                }
                case 4 -> {                                                 // дальность и высота +12
                    if (t - since.get() < 6) return;
                    Map<String, Object> c = FlightTestSupport.commands(level, cc, at[0]);
                    FcsMod.LOG.info("[radar_fcs test] flight_bridge: distance+altitude - {}", c);
                    if (!(num(c, VEL + "BODY_VELOCITY_Y") > 0)) h.fail("expected climbing (+Y): " + c);
                    if (level.getBlockEntity(h.absolutePos(new BlockPos(2, 2, 44))) instanceof RadarMonitorBlockEntity m) m.master().designate(null, null);
                    phase.set(5);
                    since.set(t);
                }
                case 5 -> {                                                 // без назначения - других целей не ведёт, висит
                    if (t - since.get() < 12) return;
                    Map<String, Object> c = FlightTestSupport.commands(level, cc, at[0]);
                    FcsMod.LOG.info("[radar_fcs test] flight_bridge: no designation - {} {}", b.state(), c);
                    if (b.goal() != null) h.fail("bridge chases another object without a designation: " + b.targetName());
                    if (b.state() != AutopilotBlockEntity.State.IDLE_HOVER) h.fail("expected IDLE_HOVER without a target, got " + b.state());
                    for (String ax : new String[]{"X", "Y", "Z"}) {
                        if (!(Math.abs(num(c, VEL + "BODY_VELOCITY_" + ax)) < 0.5)) h.fail("hovering ship moves along " + ax + ": " + c);
                    }
                    level.setBlock(at[0].north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);   // рядом с мостом (с севера свободно)
                    phase.set(6);
                    since.set(t);
                }
                case 6 -> {                                                 // пауза: аренда отдана
                    if (t - since.get() < 12) return;
                    Map<String, Object> c = FlightTestSupport.commands(level, cc, at[0]);
                    if (b.state() != AutopilotBlockEntity.State.PAUSED || b.goal() != null) h.fail("redstone did not pause: " + b.state());
                    if (!c.isEmpty()) h.fail("lease not released on pause: " + c);
                    level.removeBlock(at[0], false);
                    phase.set(7);
                }
                case 7 -> {
                    if (!FlightTestSupport.computerForgot(level, cc, at[0])) h.fail("computer still lists the removed bridge");
                    h.succeed();
                }
                default -> { }
            }
        });
    }
}
