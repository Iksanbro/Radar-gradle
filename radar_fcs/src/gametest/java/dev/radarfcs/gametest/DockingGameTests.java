package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.floor;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AutopilotBlockEntity;
import dev.radarfcs.block.DockingBlocks;
import dev.radarfcs.block.DockingControllerBlockEntity;
import dev.radarfcs.compat.DockingPorts;
import dev.radarfcs.core.Docking;
import dev.radarfcs.dock.DockingRegistry;
import dev.radarfcs.link.LinkRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Стыковочный контроллер (нужны Create: Flight Control и Create Simulated из Create Aeronautics, для моста - ещё
 * Sable; без них тесты пропускаются). Физику подлёта игровой тест не проверяет (корабль Sable в тесте сам не летит),
 * проверяются команды моста регуляторам Flight Control, выбор порта и выдвижение портов Simulated.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class DockingGameTests {
    private static final String ARENA = "arena";
    private static final String VEL = "BODY_VELOCITY:";
    /** Чужой штекер для брони портов в тесте. */
    private static final long OTHER = 0x5EED_0000_0001L;

    @BeforeBatch(batch = "docking") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "docking") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }

    private static boolean mods(boolean sable) {
        return ModList.get().isLoaded("create_flight_control") && ModList.get().isLoaded("simulated")
                && (!sable || ModList.get().isLoaded("sable"));
    }

    private static double num(Map<String, Object> m, String key) {
        return m.get(key) instanceof Double d ? d : Double.NaN;
    }

    /** Порт Simulated, смотрит в сторону facing. */
    static BlockState connector(Direction facing) {
        return BuiltInRegistries.BLOCK.get(DockingPorts.CONNECTOR).defaultBlockState().setValue(BlockStateProperties.FACING, facing);
    }

    /** Порт Simulated в connectorRel и стыковочный контроллер в rel (роль, номер), связанные; линк сети net сверху контроллера. */
    static DockingControllerBlockEntity dock(GameTestHelper h, BlockPos rel, Docking.Role role, int number, BlockPos connectorRel,
                                             Direction facing, UUID net) {
        h.setBlock(connectorRel, connector(facing));
        h.setBlock(rel, DockingBlocks.BLOCK.get().defaultBlockState());
        FcsGameTests.CREATED.add(h.absolutePos(rel));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(rel)) instanceof DockingControllerBlockEntity be)) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("no docking controller at " + rel);
        }
        CompoundTag t = new CompoundTag();
        t.putString("Role", role.name());
        t.putInt("Number", number);
        be.applySettings(t);
        be.toggleConnector(h.absolutePos(connectorRel));
        FcsGameTests.placeLink(h, rel, Direction.UP, net);
        return be;
    }

    /**
     * Контроллер на земле: связь только с портом на той же постройке не дальше 16 блоков; роль «порт» - в реестре с
     * номером, сетью и мировым концом порта (центр + 1.5 по направлению); бронь с «выдвигать» - порт Simulated
     * выдвинут, бронь снята - вернулся к своему сигналу (нет). Роль «штекер» - из реестра ушёл, выдвигается по команде
     * моста. Блок убран - порт задвинут.
     */
    @GameTest(template = ARENA, batch = "docking", timeoutTicks = 300)
    public static void controllerRegistersAndPowersConnector(GameTestHelper h) {
        if (!mods(false)) {
            h.succeed();
            return;
        }
        floor(h);
        UUID net = UUID.randomUUID();
        BlockPos rel = new BlockPos(11, 2, 10), conRel = new BlockPos(10, 2, 10);
        DockingControllerBlockEntity be = dock(h, rel, Docking.Role.PORT, 5, conRel, Direction.UP, net);
        ServerLevel lv = h.getLevel();
        BlockPos pos = h.absolutePos(rel), con = h.absolutePos(conRel);
        h.setBlock(new BlockPos(40, 2, 10), connector(Direction.UP));
        if (DockingPorts.linkable(lv, pos, h.absolutePos(new BlockPos(40, 2, 10)), DockingControllerBlockEntity.LINK_RADIUS)) {
            h.fail("a connector 29 blocks away must not be linkable");
        }
        if (!DockingPorts.linkable(lv, pos, con, DockingControllerBlockEntity.LINK_RADIUS)) h.fail("adjacent connector must be linkable");
        // второй порт вверх - добавлен; порт на север - не добавлен (порты контроллера смотрят в одну сторону)
        BlockPos con2Rel = new BlockPos(11, 2, 12), con3Rel = new BlockPos(11, 2, 7);
        h.setBlock(con2Rel, connector(Direction.UP));
        h.setBlock(con3Rel, connector(Direction.NORTH));
        BlockPos con2 = h.absolutePos(con2Rel);
        if (be.toggleConnector(con2) != DockingControllerBlockEntity.LinkResult.ADDED) h.fail("second connector not added");
        if (be.toggleConnector(h.absolutePos(con3Rel)) != DockingControllerBlockEntity.LinkResult.FACING) h.fail("connector facing north accepted");
        if (be.connectorCount() != 2) h.fail("expected 2 connectors, got " + be.connectorCount());
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            long now = level.getGameTime();
            switch (phase.get()) {
                case 0 -> {                                                 // в реестре: номер, сеть, конец порта
                    if (t < 30) return;
                    DockingRegistry.PortInfo p = DockingRegistry.get(level, pos);
                    if (p == null) {
                        h.fail("port not registered, status " + be.status());
                        return;
                    }
                    double dy = p.tip().y() - (con.getY() + 0.5 + DockingPorts.TIP);
                    // середина концов двух портов и расстановка между ними
                    double dx = p.tip().x() - (con.getX() + con2.getX() + 1) / 2.0, dz = p.tip().z() - (con.getZ() + con2.getZ() + 1) / 2.0;
                    if (p.count() != 2 || Math.abs(dx) > 1e-6 || Math.abs(dz) > 1e-6 || Math.abs(p.tips().get(0).distance(p.tips().get(1)) - Math.sqrt(5)) > 1e-6) {
                        h.fail("bad two-connector port: " + p);
                    }
                    FcsMod.LOG.info("[radar_fcs test] docking: port #{} nets {} tip {} facing {}", p.number(), p.networks(), p.tip(), p.facing());
                    if (p.number() != 5 || !p.networks().contains(net) || Math.abs(dy) > 1e-6 || p.facing().y() < 0.99 || p.locked()) {
                        h.fail("bad port entry: " + p);
                    }
                    if (DockingRegistry.ports(level, java.util.Set.of(net), 5, null).size() != 1) h.fail("port not found by number");
                    if (!DockingRegistry.ports(level, java.util.Set.of(net), 4, null).isEmpty()) h.fail("port found by a wrong number");
                    if (DockingPorts.isPowered(level, con)) h.fail("connector extended without a reservation");
                    phase.set(1);
                    since.set(t);
                }
                case 1 -> {                                                 // бронь с «выдвигать»: выдвинут каждый тик, пластина стоит
                    DockingRegistry.reserve(level, pos, OTHER, now, true);
                    if (t - since.get() < 3) return;
                    if (!DockingPorts.isPowered(level, con) || !DockingPorts.isPowered(level, con2)) {
                        h.fail("reserved port connectors not extended at tick " + (t - since.get()));
                    }
                    if (t - since.get() < 60) return;
                    BlockState front = level.getBlockState(con.above());
                    FcsMod.LOG.info("[radar_fcs test] docking: extended connector, in front {}", front);
                    if (!"paired_docking_connector".equals(BuiltInRegistries.BLOCK.getKey(front.getBlock()).getPath())) {
                        h.fail("connector did not extend its plate: " + front);
                    }
                    if (be.status() != DockingControllerBlockEntity.Status.EXTENDED && be.status() != DockingControllerBlockEntity.Status.LOCKED) {
                        h.fail("status with extended connector: " + be.status());
                    }
                    DockingRegistry.release(level, pos, OTHER);
                    phase.set(2);
                    since.set(t);
                }
                case 2 -> {                                                 // бронь снята - задвинут
                    if (t - since.get() < 30) return;
                    if (DockingPorts.isPowered(level, con) || DockingPorts.isPowered(level, con2)) h.fail("connectors still extended after the reservation ended");
                    CompoundTag s = new CompoundTag();
                    s.putString("Role", Docking.Role.PLUG.name());
                    s.putInt("Number", 5);
                    be.applySettings(s);
                    phase.set(3);
                    since.set(t);
                }
                case 3 -> {                                                 // штекер: не в реестре, выдвигается по команде
                    if (DockingRegistry.get(level, pos) != null) h.fail("plug still registered as a port");
                    if (!lv.getBlockState(pos).getValue(dev.radarfcs.block.DockingControllerBlock.PLUG)) h.fail("block state not switched to plug");
                    be.commandExtend(now);
                    if (t - since.get() < 8) return;
                    if (!DockingPorts.isPowered(level, con)) h.fail("plug did not extend its connector on command");
                    level.removeBlock(pos, false);
                    phase.set(4);
                    since.set(t);
                }
                case 4 -> {                                                 // блок убран - порт задвинут, реестр пуст
                    if (t - since.get() < 3) return;
                    if (DockingPorts.isPowered(level, con) || DockingPorts.isPowered(level, con2)) h.fail("connectors stay extended after the controller was removed");
                    if (DockingRegistry.get(level, pos) != null) h.fail("removed controller still registered");
                    h.succeed();
                }
                default -> { }
            }
        });
    }

    /**
     * Мост на постройке Sable со штекером (порт Simulated смотрит на восток) и регуляторами Flight Control. На земле в
     * той же сети порты: №7 ближний (запад), №7 дальний (восток), №3 на юге. «Стыковка: нет цели», целей нет - мост
     * ведёт корабль к ближнему порту №7 и занимает его. Ближний занят другим кораблём - к дальнему. Заняты оба: «ждать» -
     * к ближнему, без брони; «не лететь» - аренда отдана. Штекер №3 - к южному порту. «Стыковка: редстоун»: без сигнала -
     * нет цели и не стыкуется, сигнал - на стыковку (не пауза), сигнал снят - бронь отпущена.
     */
    @GameTest(template = ARENA, batch = "docking", timeoutTicks = 900)
    public static void bridgeDocksByNumber(GameTestHelper h) {
        if (!mods(true)) {
            h.succeed();
            return;
        }
        floor(h);
        UUID net = UUID.randomUUID();
        for (int x = 10; x <= 12; x++) for (int z = 30; z <= 32; z++) h.setBlock(new BlockPos(x, 4, z), Blocks.IRON_BLOCK.defaultBlockState());
        BlockPos bridgeRel = new BlockPos(11, 5, 31);
        BlockPos dInd = new BlockPos(-1, 0, -1), dCc = new BlockPos(1, 0, 1), dVel = new BlockPos(-1, 0, 1), dAng = new BlockPos(1, 0, -1),
                dAtt = new BlockPos(-1, 0, 0), dAlt = new BlockPos(1, 0, 0);
        h.setBlock(bridgeRel, FlightTestSupport.bridge(Direction.NORTH));
        h.setBlock(bridgeRel.offset(dInd), FlightTestSupport.indicator(Direction.NORTH));
        h.setBlock(bridgeRel.offset(dCc), FlightTestSupport.computer());
        h.setBlock(bridgeRel.offset(dVel), FlightTestSupport.fc("velocity_coupler"));
        h.setBlock(bridgeRel.offset(dAng), FlightTestSupport.fc("angular_velocity_coupler"));
        h.setBlock(bridgeRel.offset(dAtt), FlightTestSupport.fc("attitude_hold"));
        h.setBlock(bridgeRel.offset(dAlt), FlightTestSupport.fc("altitude_hold"));
        FcsGameTests.placeLink(h, bridgeRel, Direction.UP, net);
        // штекер корабля: контроллер (10,6,30), порт Simulated (12,6,32) смотрит на восток
        dock(h, new BlockPos(10, 6, 30), Docking.Role.PLUG, 7, new BlockPos(12, 6, 32), Direction.EAST, net);
        ServerLevel lv = h.getLevel();
        BlockPos ab = h.absolutePos(bridgeRel), acc = ab.offset(dCc);
        FlightTestSupport.startComputer(lv, acc);
        Object sub = SableTestSupport.staticSub(h, new BlockPos(10, 4, 30), new BlockPos(12, 7, 32));
        // порты на земле
        DockingControllerBlockEntity near = dock(h, new BlockPos(1, 6, 31), Docking.Role.PORT, 7, new BlockPos(2, 6, 31), Direction.EAST, net);
        DockingControllerBlockEntity far = dock(h, new BlockPos(41, 6, 31), Docking.Role.PORT, 7, new BlockPos(40, 6, 31), Direction.WEST, net);
        DockingControllerBlockEntity south = dock(h, new BlockPos(20, 6, 51), Docking.Role.PORT, 3, new BlockPos(20, 6, 50), Direction.NORTH, net);
        BlockPos nearPos = near.getBlockPos(), farPos = far.getBlockPos(), southPos = south.getBlockPos();
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        Object[] bridge = new Object[1];
        BlockPos[] at = new BlockPos[1];
        DockingControllerBlockEntity[] plug = new DockingControllerBlockEntity[1];
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            long now = level.getGameTime();
            if (bridge[0] == null) {
                for (BlockPos p : LinkRegistry.members(level, net)) {
                    if (FlightTestSupport.isBridge(level.getBlockEntity(p))) {
                        bridge[0] = level.getBlockEntity(p);
                        at[0] = p;
                    }
                    if (level.getBlockEntity(p) instanceof DockingControllerBlockEntity d && d.role == Docking.Role.PLUG) plug[0] = d;
                }
                if (bridge[0] == null || plug[0] == null) {
                    if (t > 100) h.fail("bridge or plug not found in the radar network");
                    bridge[0] = null;
                    return;
                }
                BlockPos cc = at[0].offset(dCc);
                if (!FlightTestSupport.linkComputer(level, bridge[0], cc)) h.fail("bridge-computer link failed");
                for (BlockPos d : new BlockPos[]{dVel, dAng, dAtt, dAlt}) FlightTestSupport.linkController(level, cc, at[0].offset(d));
                AutopilotBlockEntity b = (AutopilotBlockEntity) bridge[0];
                b.dockTrigger = Docking.Trigger.NO_TARGET;
                b.dockBusy = Docking.Busy.WAIT;
                since.set(t);
                return;
            }
            AutopilotBlockEntity b = (AutopilotBlockEntity) bridge[0];
            BlockPos cc = at[0].offset(dCc);
            long key = plug[0].key();
            Map<String, Object> c = FlightTestSupport.commands(level, cc, at[0]);
            switch (phase.get()) {
                case 0 -> {                                                 // нет цели - к ближнему порту №7
                    if (t - since.get() < 70) return;
                    DockingRegistry.Reservation r = DockingRegistry.reservation(level, nearPos, now);
                    FcsMod.LOG.info("[radar_fcs test] docking: no target - {} {} reservation {}", b.state(), c, r);
                    if (!b.state().name().startsWith("DOCK_")) h.fail("expected docking without a target, got " + b.state());
                    if (r == null || r.owner() != key) h.fail("near port #7 not reserved by the plug: " + r);
                    if (!(num(c, VEL + "BODY_VELOCITY_X") < 0)) h.fail("expected velocity toward the near port (-X): " + c);
                    if (!"REQUEST_OFF".equals(c.get("ALTITUDE_HOLD:participation"))) h.fail("altitude hold not suppressed: " + c);
                    phase.set(1);
                    since.set(t);
                }
                case 1 -> {                                                 // ближний занят - к дальнему
                    DockingRegistry.reserve(level, nearPos, OTHER, now, false);
                    if (t - since.get() < 15) return;
                    DockingRegistry.Reservation r = DockingRegistry.reservation(level, farPos, now);
                    FcsMod.LOG.info("[radar_fcs test] docking: near busy - {} {} far reservation {}", b.state(), c, r);
                    if (r == null || r.owner() != key) h.fail("far port #7 not reserved by the plug: " + r);
                    if (!(num(c, VEL + "BODY_VELOCITY_X") > 0)) h.fail("expected velocity toward the far port (+X): " + c);
                    phase.set(2);
                    since.set(t);
                }
                case 2 -> {                                                 // оба заняты, «ждать» - к ближнему, без брони
                    DockingRegistry.reserve(level, nearPos, OTHER, now, false);
                    DockingRegistry.reserve(level, farPos, OTHER, now, false);
                    if (t - since.get() < 15) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: all busy, wait - {} {}", b.state(), c);
                    if (b.state() != AutopilotBlockEntity.State.DOCK_TRANSIT && b.state() != AutopilotBlockEntity.State.DOCK_WAITING) {
                        h.fail("expected waiting at the nearest port, got " + b.state());
                    }
                    if (!(num(c, VEL + "BODY_VELOCITY_X") < 0)) h.fail("expected waiting near the nearest port (-X): " + c);
                    b.dockBusy = Docking.Busy.STAY;
                    phase.set(3);
                    since.set(t);
                }
                case 3 -> {                                                 // оба заняты, «не лететь» - аренда отдана
                    DockingRegistry.reserve(level, nearPos, OTHER, now, false);
                    DockingRegistry.reserve(level, farPos, OTHER, now, false);
                    if (t - since.get() < 15) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: all busy, stay - {} {}", b.state(), c);
                    if (b.state() != AutopilotBlockEntity.State.DOCK_PORTS_BUSY) h.fail("expected DOCK_PORTS_BUSY, got " + b.state());
                    if (!c.isEmpty()) h.fail("lease kept with all ports busy and 'stay': " + c);
                    CompoundTag s = plug[0].writeSettings();
                    s.putInt("Number", 3);
                    plug[0].applySettings(s);
                    phase.set(4);
                    since.set(t);
                }
                case 4 -> {                                                 // штекер №3 - к южному порту
                    if (t - since.get() < 30) return;
                    DockingRegistry.Reservation r = DockingRegistry.reservation(level, southPos, now);
                    FcsMod.LOG.info("[radar_fcs test] docking: plug #3 - {} {} reservation {}", b.state(), c, r);
                    if (r == null || r.owner() != key) h.fail("south port #3 not reserved by the plug: " + r);
                    if (!(num(c, VEL + "BODY_VELOCITY_Z") > 0)) h.fail("expected velocity toward the south port (+Z): " + c);
                    b.dockTrigger = Docking.Trigger.REDSTONE;
                    phase.set(5);
                    since.set(t);
                }
                case 5 -> {                                                 // «редстоун» без сигнала - не стыкуется, висит
                    if (t - since.get() < 15) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: redstone mode, no signal - {} {}", b.state(), c);
                    if (b.state() != AutopilotBlockEntity.State.IDLE_HOVER) h.fail("expected IDLE_HOVER without a signal, got " + b.state());
                    if (DockingRegistry.reservation(level, southPos, now) != null) h.fail("south port reserved without a signal");
                    level.setBlock(at[0].north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                    phase.set(6);
                    since.set(t);
                }
                case 6 -> {                                                 // сигнал - на стыковку, не пауза
                    if (t - since.get() < 15) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: redstone signal - {} {}", b.state(), c);
                    if (b.state() == AutopilotBlockEntity.State.PAUSED || !b.state().name().startsWith("DOCK_")) {
                        h.fail("redstone in docking mode must dock, got " + b.state());
                    }
                    if (!(num(c, VEL + "BODY_VELOCITY_Z") > 0)) h.fail("expected velocity toward the south port (+Z): " + c);
                    level.removeBlock(at[0].north(), false);
                    phase.set(7);
                    since.set(t);
                }
                case 7 -> {                                                 // сигнал снят - бронь отпущена, висит
                    if (t - since.get() < 15) return;
                    if (DockingRegistry.reservation(level, southPos, now) != null) h.fail("south port still reserved after the signal ended");
                    if (b.state() != AutopilotBlockEntity.State.IDLE_HOVER) h.fail("expected IDLE_HOVER after the signal ended, got " + b.state());
                    h.succeed();
                }
                default -> { }
            }
        });
    }

    // ------------------------------------------------------------- корабль с мостом для тестов ниже

    private static final BlockPos BRIDGE = new BlockPos(11, 5, 31);
    private static final BlockPos D_IND = new BlockPos(-1, 0, -1), D_CC = new BlockPos(1, 0, 1), D_VEL = new BlockPos(-1, 0, 1),
            D_ANG = new BlockPos(1, 0, -1), D_ATT = new BlockPos(-1, 0, 0), D_ALT = new BlockPos(1, 0, 0);

    /** Палуба 3x3 (y=4), мост (11,5,31) с компьютером, регуляторами скорости и угловой скорости, стабилизаторами; линк сверху. */
    private static void shipBase(GameTestHelper h, UUID net) {
        for (int x = 10; x <= 12; x++) for (int z = 30; z <= 32; z++) h.setBlock(new BlockPos(x, 4, z), Blocks.IRON_BLOCK.defaultBlockState());
        h.setBlock(BRIDGE, FlightTestSupport.bridge(Direction.NORTH));
        h.setBlock(BRIDGE.offset(D_IND), FlightTestSupport.indicator(Direction.NORTH));
        h.setBlock(BRIDGE.offset(D_CC), FlightTestSupport.computer());
        h.setBlock(BRIDGE.offset(D_VEL), FlightTestSupport.fc("velocity_coupler"));
        h.setBlock(BRIDGE.offset(D_ANG), FlightTestSupport.fc("angular_velocity_coupler"));
        h.setBlock(BRIDGE.offset(D_ATT), FlightTestSupport.fc("attitude_hold"));
        h.setBlock(BRIDGE.offset(D_ALT), FlightTestSupport.fc("altitude_hold"));
        FcsGameTests.placeLink(h, BRIDGE, Direction.UP, net);
        FlightTestSupport.startComputer(h.getLevel(), h.absolutePos(BRIDGE.offset(D_CC)));
    }

    /** Найти мост корабля (и штекер) в сети и связать с компьютером и регуляторами; false - ещё не нашёлся. */
    private static boolean findShip(GameTestHelper h, UUID net, Object[] bridge, BlockPos[] at, DockingControllerBlockEntity[] plug) {
        ServerLevel level = h.getLevel();
        for (BlockPos p : LinkRegistry.members(level, net)) {
            if (FlightTestSupport.isBridge(level.getBlockEntity(p))) {
                bridge[0] = level.getBlockEntity(p);
                at[0] = p;
            }
            if (plug != null && level.getBlockEntity(p) instanceof DockingControllerBlockEntity d && d.role == Docking.Role.PLUG) plug[0] = d;
        }
        if (bridge[0] == null || (plug != null && plug[0] == null)) {
            bridge[0] = null;
            return false;
        }
        BlockPos cc = at[0].offset(D_CC);
        if (!FlightTestSupport.linkComputer(level, bridge[0], cc)) h.fail("bridge-computer link failed");
        for (BlockPos d : new BlockPos[]{D_VEL, D_ANG, D_ATT, D_ALT}) FlightTestSupport.linkController(level, cc, at[0].offset(d));
        return true;
    }

    /**
     * Без цели мост других объектов не ведёт: «висеть» - скорости около нуля, корпус ровно; «орбита вокруг координаты»
     * (центр в 30 бл к востоку, радиус 10) - к центру (+X) и против часовой - на юг (+Z), по часовой - на север (-Z);
     * «орбита вокруг маяка» без маяка - висит, маяк к западу в сети - к нему (-X), «летит по орбите».
     */
    @GameTest(template = ARENA, batch = "docking", timeoutTicks = 400)
    public static void bridgeIdleHoverAndOrbit(GameTestHelper h) {
        if (!mods(true)) {
            h.succeed();
            return;
        }
        floor(h);
        UUID net = UUID.randomUUID();
        shipBase(h, net);
        SableTestSupport.staticSub(h, new BlockPos(10, 4, 30), new BlockPos(12, 6, 32));
        BlockPos shipAbs = h.absolutePos(BRIDGE);
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        Object[] bridge = new Object[1];
        BlockPos[] at = new BlockPos[1];
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            if (bridge[0] == null) {
                if (!findShip(h, net, bridge, at, null)) {
                    if (t > 100) h.fail("bridge not found in the radar network");
                    return;
                }
                since.set(t);
                return;
            }
            AutopilotBlockEntity b = (AutopilotBlockEntity) bridge[0];
            Map<String, Object> c = FlightTestSupport.commands(level, at[0].offset(D_CC), at[0]);
            double vx = num(c, VEL + "BODY_VELOCITY_X"), vz = num(c, VEL + "BODY_VELOCITY_Z");
            switch (phase.get()) {
                case 0 -> {                                                 // висеть
                    if (t - since.get() < 20) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: idle hover - {} {}", b.state(), c);
                    if (b.state() != AutopilotBlockEntity.State.IDLE_HOVER) h.fail("expected IDLE_HOVER, got " + b.state());
                    if (!(Math.abs(vx) < 0.5 && Math.abs(vz) < 0.5)) h.fail("hovering ship moves: " + c);
                    if (!"REQUEST_OFF".equals(c.get("ALTITUDE_HOLD:participation"))) h.fail("altitude hold not suppressed: " + c);
                    b.idle = dev.radarfcs.core.Autopilot.Idle.ORBIT_POINT;
                    b.orbitX = shipAbs.getX() + 30.5;
                    b.orbitY = shipAbs.getY();
                    b.orbitZ = shipAbs.getZ() + 0.5;
                    b.orbitRadius = 10;
                    b.orbitHeight = 0;
                    b.orbitClockwise = false;
                    phase.set(1);
                    since.set(t);
                }
                case 1 -> {                                                 // орбита против часовой
                    if (t - since.get() < 12) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: orbit ccw - {} {}", b.state(), c);
                    if (b.state() != AutopilotBlockEntity.State.IDLE_ORBIT) h.fail("expected IDLE_ORBIT, got " + b.state());
                    if (!(vx > 0 && vz > 0)) h.fail("expected toward the center (+X) and counterclockwise (+Z): " + c);
                    b.orbitClockwise = true;
                    phase.set(2);
                    since.set(t);
                }
                case 2 -> {                                                 // по часовой
                    if (t - since.get() < 12) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: orbit cw - {} {}", b.state(), c);
                    if (!(vx > 0 && vz < 0)) h.fail("expected toward the center (+X) and clockwise (-Z): " + c);
                    b.idle = dev.radarfcs.core.Autopilot.Idle.ORBIT_BEACON;
                    b.orbitRadius = 4;
                    phase.set(3);
                    since.set(t);
                }
                case 3 -> {                                                 // маяка нет - висит
                    if (t - since.get() < 12) return;
                    if (b.state() != AutopilotBlockEntity.State.IDLE_NO_BEACON) h.fail("expected IDLE_NO_BEACON, got " + b.state());
                    h.setBlock(new BlockPos(2, 2, 31), DockingBlocks.BEACON.get().defaultBlockState());
                    FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(2, 2, 31)));
                    FcsGameTests.placeLink(h, new BlockPos(2, 2, 31), Direction.UP, net);
                    phase.set(4);
                    since.set(t);
                }
                case 4 -> {                                                 // маяк к западу - к нему
                    if (t - since.get() < 30) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: orbit beacon - {} {}", b.state(), c);
                    if (b.state() != AutopilotBlockEntity.State.IDLE_ORBIT) h.fail("expected IDLE_ORBIT around the beacon, got " + b.state());
                    if (!(vx < 0)) h.fail("expected toward the beacon (-X): " + c);
                    // маяк с кодом ALPHA к востоку, не в сети моста; мост с кодом ALPHA - к нему
                    h.setBlock(new BlockPos(40, 2, 31), DockingBlocks.BEACON.get().defaultBlockState());
                    FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(40, 2, 31)));
                    if (level.getBlockEntity(h.absolutePos(new BlockPos(40, 2, 31))) instanceof dev.radarfcs.block.OrbitBeaconBlockEntity ob) {
                        ob.setCode("  ALPHA\t ");
                        if (!"ALPHA".equals(ob.code())) h.fail("beacon code not cleaned: '" + ob.code() + "'");
                    }
                    b.orbitCode = "alpha";
                    phase.set(5);
                    since.set(t);
                }
                case 5 -> {                                                 // по коду (без учёта регистра) - к восточному
                    if (t - since.get() < 30) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: orbit beacon by code - {} {}", b.state(), c);
                    if (b.state() != AutopilotBlockEntity.State.IDLE_ORBIT) h.fail("expected IDLE_ORBIT around the coded beacon, got " + b.state());
                    if (!(vx > 0)) h.fail("expected toward the coded beacon (+X): " + c);
                    b.orbitCode = "BRAVO";
                    phase.set(6);
                    since.set(t);
                }
                case 6 -> {                                                 // кода нет ни у одного маяка - висит
                    if (t - since.get() < 12) return;
                    if (b.state() != AutopilotBlockEntity.State.IDLE_NO_BEACON) h.fail("expected IDLE_NO_BEACON for an unknown code, got " + b.state());
                    h.succeed();
                }
                default -> { }
            }
        });
    }

    /**
     * Пристыкован: два порта корабля (вниз) над двумя портами на земле (вверх), все выдвинуты - сцепились оба. Мост («стыковка по
     * редстоуну», сигнал есть) видит сцепку: «пристыкован», компьютер управления полётом выключен (тяга двигателей
     * снята), корабль отмечен - пушки вычислителей на нём неактивны. Сигнал снят - расстыковка, компьютер снова работает,
     * отметки нет.
     */
    @GameTest(template = ARENA, batch = "docking", timeoutTicks = 600)
    public static void dockedShipPowersDown(GameTestHelper h) {
        if (!mods(true)) {
            h.succeed();
            return;
        }
        floor(h);
        UUID net = UUID.randomUUID();
        shipBase(h, net);
        // по два порта: у корабля снизу (10,3,31) и (12,3,31), на земле под ними
        DockingControllerBlockEntity plugBe = dock(h, new BlockPos(10, 6, 30), Docking.Role.PLUG, 9, new BlockPos(10, 3, 31), Direction.DOWN, net);
        h.setBlock(new BlockPos(12, 3, 31), connector(Direction.DOWN));
        plugBe.toggleConnector(h.absolutePos(new BlockPos(12, 3, 31)));
        Object sub = SableTestSupport.staticSub(h, new BlockPos(10, 3, 30), new BlockPos(12, 7, 32));
        UUID shipId = SableTestSupport.subId(sub);
        DockingControllerBlockEntity port = dock(h, new BlockPos(14, 0, 31), Docking.Role.PORT, 9, new BlockPos(10, 0, 31), Direction.UP, net);
        h.setBlock(new BlockPos(12, 0, 31), connector(Direction.UP));
        port.toggleConnector(h.absolutePos(new BlockPos(12, 0, 31)));
        BlockPos portPos = port.getBlockPos();
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        Object[] bridge = new Object[1];
        BlockPos[] at = new BlockPos[1];
        DockingControllerBlockEntity[] plug = new DockingControllerBlockEntity[1];
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            long now = level.getGameTime();
            if (bridge[0] == null) {
                if (!findShip(h, net, bridge, at, plug)) {
                    if (t > 100) h.fail("bridge or plug not found in the radar network");
                    return;
                }
                // сигнал на мосту - пока пауза (стыковка выкл); порты выдвигает тест, после сцепки - «стыковка по редстоуну»
                level.setBlock(at[0].north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                since.set(t);
                return;
            }
            AutopilotBlockEntity b = (AutopilotBlockEntity) bridge[0];
            BlockPos cc = at[0].offset(D_CC);
            switch (phase.get()) {
                case 0 -> {                                                 // выдвинуть оба порта, ждать сцепки
                    plug[0].commandExtend(now);
                    DockingRegistry.reserve(level, portPos, plug[0].key(), now, true);
                    if (plug[0].lockedCount() < 2) {
                        if (t - since.get() > 300) h.fail("not both connectors locked (" + plug[0].lockedCount() + "), plug " + plug[0].status()
                                + ", port " + port.status());
                        return;
                    }
                    FcsMod.LOG.info("[radar_fcs test] docking: both connectors locked after {} ticks", t - since.get());
                    b.dockTrigger = Docking.Trigger.REDSTONE;
                    phase.set(1);
                    since.set(t);
                }
                case 1 -> {                                                 // пристыкован: компьютер выключен, корабль отмечен
                    DockingRegistry.reserve(level, portPos, plug[0].key(), now, true);
                    if (t - since.get() < 12) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: docked - {} computer working {} held {}", b.state(),
                            FlightTestSupport.working(level, cc), dev.radarfcs.dock.DockHold.held(level, shipId, now));
                    if (b.state() != AutopilotBlockEntity.State.DOCKED) h.fail("expected DOCKED, got " + b.state());
                    if (FlightTestSupport.working(level, cc)) h.fail("flight control computer still working while docked");
                    if (!dev.radarfcs.dock.DockHold.held(level, shipId, now)) h.fail("docked ship not held (guns would stay active)");
                    level.removeBlock(at[0].north(), false);
                    phase.set(2);
                    since.set(t);
                }
                case 2 -> {                                                 // сигнал снят - расстыковка
                    if (t - since.get() < 20) return;
                    FcsMod.LOG.info("[radar_fcs test] docking: undocked - {} computer working {} locked {}", b.state(),
                            FlightTestSupport.working(level, cc), plug[0].locked());
                    if (!FlightTestSupport.working(level, cc)) h.fail("flight control computer not restored after undocking");
                    if (dev.radarfcs.dock.DockHold.held(level, shipId, now)) h.fail("ship still held after undocking");
                    if (plug[0].locked()) h.fail("still locked after the signal ended");
                    h.succeed();
                }
                default -> { }
            }
        });
    }

    /**
     * Доворот по расстановке: у корабля два порта снизу поперёк (по X), у станции №4 два порта вверх вдоль Z - мост на
     * стыковке («нет цели») даёт команду поворота вокруг вертикали; расстановки совпадают (по 2, через 2 бл).
     */
    @GameTest(template = ARENA, batch = "docking", timeoutTicks = 300)
    public static void bridgeTurnsForPortLayout(GameTestHelper h) {
        if (!mods(true)) {
            h.succeed();
            return;
        }
        floor(h);
        UUID net = UUID.randomUUID();
        shipBase(h, net);
        DockingControllerBlockEntity plugBe = dock(h, new BlockPos(10, 6, 30), Docking.Role.PLUG, 4, new BlockPos(10, 3, 31), Direction.DOWN, net);
        h.setBlock(new BlockPos(12, 3, 31), connector(Direction.DOWN));
        plugBe.toggleConnector(h.absolutePos(new BlockPos(12, 3, 31)));
        SableTestSupport.staticSub(h, new BlockPos(10, 3, 30), new BlockPos(12, 7, 32));
        DockingControllerBlockEntity station = dock(h, new BlockPos(41, 1, 21), Docking.Role.PORT, 4, new BlockPos(40, 1, 20), Direction.UP, net);
        h.setBlock(new BlockPos(40, 1, 22), connector(Direction.UP));
        station.toggleConnector(h.absolutePos(new BlockPos(40, 1, 22)));
        AtomicInteger since = new AtomicInteger();
        Object[] bridge = new Object[1];
        BlockPos[] at = new BlockPos[1];
        DockingControllerBlockEntity[] plug = new DockingControllerBlockEntity[1];
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            if (bridge[0] == null) {
                if (!findShip(h, net, bridge, at, plug)) {
                    if (t > 100) h.fail("bridge or plug not found in the radar network");
                    return;
                }
                ((AutopilotBlockEntity) bridge[0]).dockTrigger = Docking.Trigger.NO_TARGET;
                since.set(t);
                return;
            }
            if (t - since.get() < 70) return;
            AutopilotBlockEntity b = (AutopilotBlockEntity) bridge[0];
            Map<String, Object> c = FlightTestSupport.commands(level, at[0].offset(D_CC), at[0]);
            double wy = num(c, "BODY_ANGULAR_VELOCITY:BODY_ANGULAR_VELOCITY_Y");
            FcsMod.LOG.info("[radar_fcs test] docking: layout turn - {} {}", b.state(), c);
            if (!b.state().name().startsWith("DOCK_")) h.fail("expected docking, got " + b.state());
            if (!(Math.abs(wy) > 0.1)) h.fail("expected a yaw command to match the port layout: " + c);
            DockingRegistry.PortInfo pi = DockingRegistry.get(level, station.getBlockPos());
            if (pi == null || pi.count() != 2) h.fail("station port not registered with 2 connectors: " + pi);
            h.succeed();
        });
    }
}
