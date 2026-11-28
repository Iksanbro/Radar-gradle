package dev.radarfcs.gametest;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.compat.Mods;
import dev.radarfcs.compat.rocket.HeatNoseBlockEntity;
import dev.radarfcs.compat.rocket.NetNoseBlockEntity;
import dev.radarfcs.compat.rocket.RocketGuidance;
import dev.radarfcs.compat.rocket.RocketNoses;
import dev.radarfcs.core.EngageSettings.Category;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Носы ракет CBC Enchanced Shells: настоящая стартовая площадка (площадка, 2 заряда, ядро, нос) смотрит на восток, цель
 * - моб в стороне от оси пуска (без наведения ракета прошла бы мимо на ~10 блоков). Тепловая головка сама захватывает
 * моба; программируемый нос - цель сети вычислителя (автопуск и пуск по редстоуну), и цель не меняется, даже когда
 * вычислитель её больше не ведёт. Каждый тест - в своём пакете: ракеты летят далеко за площадку.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class RocketGameTests {
    private static final String ARENA = "arena";
    /** Промах «попадания»: ближе этого к центру моба ракета прошла - попала (ядро бьёт по габариту моба). */
    private static final double HIT = 2.5;

    @BeforeBatch(batch = "rocket_heat") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "rocket_heat") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "rocket_heat_ship") public static void b4(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "rocket_heat_ship") public static void a4(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "rocket_net") public static void b2(ServerLevel l) { FcsGameTests.direct(l, 200); }
    @AfterBatch(batch = "rocket_net") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "rocket_net_rs") public static void b3(ServerLevel l) { FcsGameTests.direct(l, 200); }
    @AfterBatch(batch = "rocket_net_rs") public static void a3(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "rocket_salvo_single") public static void b6(ServerLevel l) { FcsGameTests.direct(l, 200); }
    @AfterBatch(batch = "rocket_salvo_single") public static void a6(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "rocket_salvo_queue") public static void b7(ServerLevel l) { FcsGameTests.direct(l, 200); }
    @AfterBatch(batch = "rocket_salvo_queue") public static void a7(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "rocket_net_des") public static void b5(ServerLevel l) { FcsGameTests.direct(l, 200); }
    @AfterBatch(batch = "rocket_net_des") public static void a5(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "rocket_burnout") public static void b8(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "rocket_burnout") public static void a8(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "rocket_salvo_reset") public static void b9(ServerLevel l) { FcsGameTests.direct(l, 200); }
    @AfterBatch(batch = "rocket_salvo_reset") public static void a9(ServerLevel l) { FcsGameTests.cleanup(l); }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    /** Ракета на стартовой площадке: площадка в base, 2 заряда, ядро, нос - вдоль dir. Возвращает позицию носа. */
    static BlockPos rocket(GameTestHelper h, BlockPos base, Direction dir, Block nose) {
        return rocket(h, base, dir, nose, 2);
    }

    /** То же с charges зарядами (заряд - 30 тиков тяги). */
    static BlockPos rocket(GameTestHelper h, BlockPos base, Direction dir, Block nose, int charges) {
        for (int y = 1; y < base.getY(); y++) h.setBlock(new BlockPos(base.getX(), y, base.getZ()), Blocks.STONE.defaultBlockState());
        h.setBlock(base, block("cbc_enhanced_shells:rocket_base").defaultBlockState().setValue(BlockStateProperties.FACING, dir));
        BlockPos p = base;
        for (int i = 0; i < charges; i++) {
            p = p.relative(dir);
            h.setBlock(p, block("createbigcannons:powder_charge").defaultBlockState().trySetValue(BlockStateProperties.AXIS, dir.getAxis()));
        }
        p = p.relative(dir);
        h.setBlock(p, block("createbigcannons:solid_shot").defaultBlockState().setValue(BlockStateProperties.FACING, dir));
        p = p.relative(dir);
        h.setBlock(p, nose.defaultBlockState().setValue(BlockStateProperties.FACING, dir));
        FcsGameTests.CREATED.add(h.absolutePos(p));
        return p;
    }

    /** Ближайший подход ракет к мобу и захваченные ими цели. */
    static final class Watch {
        double min = Double.POSITIVE_INFINITY;
        String lock;
        int rocketId = -1;
        int launchedAt = -1;

        void tick(ServerLevel level, Mob target, int t) {
            for (RocketGuidance.Guided g : RocketGuidance.guided(level)) {
                if (rocketId < 0) {
                    rocketId = g.rocket().getId();
                    launchedAt = t;
                }
                if (g.rocket().getId() != rocketId) continue;
                if (g.lock() != null) lock = g.lock();
                double d = g.rocket().position().distanceTo(target.getBoundingBox().getCenter());
                min = Math.min(min, d);
            }
        }
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * Тепловая головка: после пуска сама захватывает враждебного моба впереди (мирную корову рядом с осью - нет) и
     * доводит ракету до него.
     */
    @GameTest(template = ARENA, batch = "rocket_heat", timeoutTicks = 300)
    public static void heatSeekerNoseLocksAndHits(GameTestHelper h) {
        if (!Mods.ENHANCED_SHELLS) {
            h.succeed();
            return;
        }
        FcsGameTests.floor(h);
        FcsGameTests.forceArea(h, new BlockPos(0, 0, -40), new BlockPos(160, 0, 70));
        FcsGameTests.removeBarriers(h, new BlockPos(-4, 1, -40), new BlockPos(160, 60, 70));   // ракеты летят за площадку
        BlockPos nose = rocket(h, new BlockPos(6, 16, 20), Direction.EAST, RocketNoses.HEAT.get());
        Mob husk = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(100.5, 14, 30.5), 0, 0));
        Mob cow = FcsGameTests.movingTarget(h, EntityType.COW, new Vec3(60.5, 15, 22.5), 0, 0);
        if (!(h.getLevel().getBlockEntity(h.absolutePos(nose)) instanceof HeatNoseBlockEntity be)) {
            h.fail("no heat seeker nose");
            return;
        }
        if ((be.categories() & HeatNoseBlockEntity.bit(Category.MOBS)) == 0) h.fail("mobs are not a default heat seeker target");
        Watch w = new Watch();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            if (t == 10) h.setBlock(new BlockPos(6, 16, 19), Blocks.REDSTONE_BLOCK.defaultBlockState());   // пуск - сигнал на площадку
            w.tick(level, husk, t);
            if (t == 12 && w.rocketId < 0) h.fail("rocket did not launch");
            if (w.lock != null && w.lock.equals(cow.getUUID().toString())) h.fail("heat seeker locked on a passive cow");
            if (t % 20 == 0) FcsMod.LOG.info("[radar_fcs test] rocket_heat t {} lock {} min {}", t, w.lock, f(w.min));
            if (w.min < HIT) {
                if (!husk.getUUID().toString().equals(w.lock)) h.fail("rocket reached the husk without a lock: " + w.lock);
                FcsMod.LOG.info("[radar_fcs test] rocket_heat: hit, closest {} after {} ticks", f(w.min), t - w.launchedAt);
                h.succeed();
            } else if (t > 260) {
                h.fail("heat seeker rocket missed: closest " + f(w.min) + ", lock " + w.lock);
            }
        });
    }

    /**
     * Тепловая головка только на корабли: берёт постройку Sable (летящий корабль из железа), моба у оси полёта
     * пропускает, ракета доходит до корабля.
     */
    @GameTest(template = ARENA, batch = "rocket_heat_ship", timeoutTicks = 300)
    public static void heatSeekerNoseTargetsShips(GameTestHelper h) {
        if (!Mods.ENHANCED_SHELLS || !net.neoforged.fml.ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        FcsGameTests.floor(h);
        FcsGameTests.forceArea(h, new BlockPos(0, 0, -40), new BlockPos(160, 0, 70));
        FcsGameTests.removeBarriers(h, new BlockPos(-4, 1, -40), new BlockPos(160, 60, 70));
        BlockPos nose = rocket(h, new BlockPos(6, 16, 20), Direction.EAST, RocketNoses.HEAT.get());
        if (!(h.getLevel().getBlockEntity(h.absolutePos(nose)) instanceof HeatNoseBlockEntity be)) {
            h.fail("no heat seeker nose");
            return;
        }
        be.setCategories(HeatNoseBlockEntity.bit(Category.SHIPS));
        Mob husk = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(70.5, 15, 21.5), 0, 0));
        Object ship = SableTestSupport.ship(h, new BlockPos(98, 13, 28), 3, new Vec3(0, 0, 0.05), 10, false);
        UUID shipId = SableTestSupport.subId(ship);
        double[] min = {Double.POSITIVE_INFINITY};
        String[] lock = {null};
        int[] rocketId = {-1};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            if (t == 10) h.setBlock(new BlockPos(6, 16, 19), Blocks.REDSTONE_BLOCK.defaultBlockState());
            var box = SableTestSupport.box(ship);
            for (RocketGuidance.Guided g : RocketGuidance.guided(level)) {
                if (rocketId[0] < 0) rocketId[0] = g.rocket().getId();
                if (g.rocket().getId() != rocketId[0]) continue;
                if (g.lock() != null) lock[0] = g.lock();
                min[0] = Math.min(min[0], Math.sqrt(box.distanceToSqr(g.rocket().position())));
            }
            if (lock[0] != null && lock[0].equals(husk.getUUID().toString())) h.fail("ships-only heat seeker locked on a mob");
            if (t % 20 == 0) FcsMod.LOG.info("[radar_fcs test] rocket_heat_ship t {} lock {} gap {}", t, lock[0], f(min[0]));
            if (min[0] < 1.0) {
                if (!shipId.toString().equals(lock[0])) h.fail("rocket reached the ship without a ship lock: " + lock[0]);
                FcsMod.LOG.info("[radar_fcs test] rocket_heat_ship: reached the ship, gap {}", f(min[0]));
                h.succeed();
            } else if (t > 260) {
                h.fail("ships-only heat seeker missed the ship: gap " + f(min[0]) + ", lock " + lock[0]);
            }
        });
    }

    /**
     * Программируемый нос, автопуск: вычислитель сети ведёт моба (автопушка), нос с допустимой целью «любая» пускает
     * ракету сам и передаёт ей эту цель; нос с допустимой целью «корабли» не пускает («не тот тип»). После пуска
     * вычислитель убирают и рядом с осью ставят второго моба - ракета всё равно летит в первого.
     */
    @GameTest(template = ARENA, batch = "rocket_net", timeoutTicks = 400)
    public static void networkNoseAutoLaunchKeepsTarget(GameTestHelper h) {
        if (!Mods.ENHANCED_SHELLS) {
            h.succeed();
            return;
        }
        FcsGameTests.floor(h);
        FcsGameTests.forceArea(h, new BlockPos(0, 0, -40), new BlockPos(160, 0, 70));
        FcsGameTests.removeBarriers(h, new BlockPos(-4, 1, -40), new BlockPos(160, 60, 70));   // ракеты летят за площадку
        BlockPos fcsPos = new BlockPos(6, 1, 50), mount = new BlockPos(10, 1, 50);
        FcsGameTests.autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity fcs = FcsGameTests.fcs(h, fcsPos);
        SettingsGameTests.configure(fcs, st -> st.dropStatic = false);          // моб в тесте неподвижен
        UUID net = FcsGameTests.networkAt(h, fcsPos);
        Mob husk = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(100.5, 14, 30.5), 0, 0));
        BlockPos nose = rocket(h, new BlockPos(6, 16, 20), Direction.EAST, RocketNoses.NET.get());
        BlockPos other = rocket(h, new BlockPos(6, 16, 10), Direction.EAST, RocketNoses.NET.get());
        if (!(h.getLevel().getBlockEntity(h.absolutePos(nose)) instanceof NetNoseBlockEntity be)
                || !(h.getLevel().getBlockEntity(h.absolutePos(other)) instanceof NetNoseBlockEntity ships)) {
            h.fail("no network nose");
            return;
        }
        if (net == null) {
            h.fail("no radar network at the computer");
            return;
        }
        be.setNetwork(net);
        ships.setNetwork(net);
        ships.apply(NetNoseBlockEntity.Mode.AUTO, Category.SHIPS.ordinal());
        Watch w = new Watch();
        Mob[] decoy = {null};
        AtomicInteger phase = new AtomicInteger();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            w.tick(level, husk, t);
            if (t % 20 == 0) FcsMod.LOG.info("[radar_fcs test] rocket_net t {} phase {} nose {} other {} lock {} min {}", t, phase.get(),
                    level.getBlockEntity(h.absolutePos(nose)) instanceof NetNoseBlockEntity n ? n.state() : "launched",
                    level.getBlockEntity(h.absolutePos(other)) instanceof NetNoseBlockEntity n2 ? n2.state() : "launched", w.lock, f(w.min));
            switch (phase.get()) {
                case 0 -> {                                                 // ждём, пока вычислитель возьмёт моба, - тогда автопуск
                    if (t == 5) be.apply(NetNoseBlockEntity.Mode.AUTO, -1);
                    if (w.rocketId >= 0) {
                        phase.set(1);
                    } else if (t > 200) {
                        h.fail("network nose did not launch: " + be.state());
                    }
                }
                case 1 -> {
                    if (w.lock == null) {
                        h.fail("network nose rocket has no target");
                        return;
                    }
                    if (!w.lock.equals(husk.getUUID().toString())) h.fail("rocket target is not the computer's target: " + w.lock);
                    // второй нос (допустимая цель - корабли) видит моба и не пускает
                    if (ships.state() != NetNoseBlockEntity.State.WRONG_TYPE) {
                        if (t - w.launchedAt > 40) h.fail("nose with trigger 'ships' is not WRONG_TYPE: " + ships.state());
                        return;
                    }
                    // вычислитель больше не ведёт цель: убрать его; второй моб - ближе к оси полёта
                    h.setBlock(fcsPos, Blocks.AIR.defaultBlockState());
                    decoy[0] = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(80.5, 15, 22.5), 0, 0));
                    phase.set(2);
                }
                default -> {
                    if (!husk.getUUID().toString().equals(w.lock)) h.fail("rocket target changed after launch: " + w.lock);
                    if (!(level.getBlockEntity(h.absolutePos(other)) instanceof NetNoseBlockEntity n2)) {
                        h.fail("nose with trigger 'ships' launched at a mob");
                        return;
                    }
                    if (w.min < HIT) {
                        FcsMod.LOG.info("[radar_fcs test] rocket_net: hit the first target, closest {}, other nose {}", f(w.min), n2.state());
                        h.succeed();
                    } else if (t - w.launchedAt > 200) {
                        h.fail("network nose rocket missed: closest " + f(w.min));
                    }
                }
            }
        });
    }

    /** Программируемый нос, пуск по редстоуну: без сигнала не уходит, сигнал на нос - ракета на цель сети. */
    @GameTest(template = ARENA, batch = "rocket_net_rs", timeoutTicks = 400)
    public static void networkNoseRedstoneLaunch(GameTestHelper h) {
        if (!Mods.ENHANCED_SHELLS) {
            h.succeed();
            return;
        }
        FcsGameTests.floor(h);
        FcsGameTests.forceArea(h, new BlockPos(0, 0, -40), new BlockPos(160, 0, 70));
        FcsGameTests.removeBarriers(h, new BlockPos(-4, 1, -40), new BlockPos(160, 60, 70));   // ракеты летят за площадку
        BlockPos fcsPos = new BlockPos(6, 1, 50), mount = new BlockPos(10, 1, 50);
        FcsGameTests.autocannon(h, mount, Direction.EAST, 4, 64);
        SettingsGameTests.configure(FcsGameTests.fcs(h, fcsPos), st -> st.dropStatic = false);
        UUID net = FcsGameTests.networkAt(h, fcsPos);
        Mob husk = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(100.5, 14, 30.5), 0, 0));
        BlockPos nose = rocket(h, new BlockPos(6, 16, 20), Direction.EAST, RocketNoses.NET.get());
        if (!(h.getLevel().getBlockEntity(h.absolutePos(nose)) instanceof NetNoseBlockEntity be) || net == null) {
            h.fail("no network nose or network");
            return;
        }
        be.setNetwork(net);
        be.apply(NetNoseBlockEntity.Mode.REDSTONE, -1);
        Watch w = new Watch();
        int[] signalAt = {-1};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            w.tick(level, husk, t);
            if (signalAt[0] < 0) {
                if (w.rocketId >= 0) h.fail("redstone-mode nose launched without a signal");
                if (be.state() == NetNoseBlockEntity.State.READY && t > 60) {
                    h.setBlock(nose.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    signalAt[0] = t;
                } else if (t > 250) {
                    h.fail("network has no target for the nose: " + be.state());
                }
                return;
            }
            if (t - signalAt[0] == 3 && w.rocketId < 0) h.fail("redstone signal did not launch the rocket");
            if (w.rocketId >= 0 && !husk.getUUID().toString().equals(w.lock)) h.fail("rocket target is not the network target: " + w.lock);
            if (w.min < HIT) {
                FcsMod.LOG.info("[radar_fcs test] rocket_net_rs: hit, closest {}", f(w.min));
                h.succeed();
            } else if (t - signalAt[0] > 200) {
                h.fail("redstone-launched rocket missed: closest " + f(w.min));
            }
        });
    }

    /**
     * Программируемый нос, автопуск только по назначенной цели: вычислитель ведёт ближнего моба, но без ручного
     * назначения нос не пускает («нет цели, назначенной вручную»); на мониторе сети назначают дальнего моба (как ПКМ по
     * отметке или целеуказателем) - ракета уходит сама и попадает именно в него.
     */
    @GameTest(template = ARENA, batch = "rocket_net_des", timeoutTicks = 400)
    public static void networkNoseAutoLaunchOnDesignationOnly(GameTestHelper h) {
        if (!Mods.ENHANCED_SHELLS) {
            h.succeed();
            return;
        }
        FcsGameTests.floor(h);
        FcsGameTests.forceArea(h, new BlockPos(0, 0, -40), new BlockPos(160, 0, 70));
        FcsGameTests.removeBarriers(h, new BlockPos(-4, 1, -40), new BlockPos(160, 60, 70));
        BlockPos fcsPos = new BlockPos(6, 1, 50), mount = new BlockPos(10, 1, 50);
        FcsGameTests.autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity fcs = FcsGameTests.fcs(h, fcsPos);
        SettingsGameTests.configure(fcs, st -> st.dropStatic = false);
        UUID net = FcsGameTests.networkAt(h, fcsPos);
        // монитор радара (стена 2x2) в той же сети
        BlockState mon = dev.radarfcs.registry.FcsRegistry.RADAR_MONITOR.get().defaultBlockState()
                .setValue(dev.radarfcs.block.RadarMonitorBlock.FACING, Direction.SOUTH);
        for (int x = 2; x <= 3; x++) for (int y = 2; y <= 3; y++) {
            h.setBlock(new BlockPos(x, y, 44), mon);
            FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(x, y, 44)));
        }
        FcsGameTests.link(h, new BlockPos(3, 3, 44), Direction.UP, fcsPos);
        Mob near = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(100.5, 14, 30.5), 0, 0));
        Mob far = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(104.5, 14, 10.5), 0, 0));
        BlockPos nose = rocket(h, new BlockPos(6, 16, 20), Direction.EAST, RocketNoses.NET.get());
        if (!(h.getLevel().getBlockEntity(h.absolutePos(nose)) instanceof NetNoseBlockEntity be) || net == null) {
            h.fail("no network nose or network");
            return;
        }
        be.setNetwork(net);
        be.apply(NetNoseBlockEntity.Mode.AUTO_DESIGNATED, -1);
        Watch w = new Watch();
        int[] designatedAt = {-1};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            w.tick(level, far, t);
            if (designatedAt[0] < 0) {
                if (w.rocketId >= 0) h.fail("nose launched without a target designated by hand: " + w.lock);
                if (t < 80) return;
                var computer = dev.radarfcs.fcs.NetworkTargets.of(level, net);
                if (computer == null) {
                    if (t > 250) h.fail("the computer has no target - test setup");
                    return;
                }
                if (be.state() != NetNoseBlockEntity.State.NO_DESIGNATION) h.fail("expected NO_DESIGNATION, got " + be.state());
                if (!(level.getBlockEntity(h.absolutePos(new BlockPos(2, 2, 44))) instanceof dev.radarfcs.block.RadarMonitorBlockEntity m)) {
                    h.fail("no radar monitor");
                    return;
                }
                FcsMod.LOG.info("[radar_fcs test] rocket_net_des: computer target {}, nose {} - designating the far husk", computer.name(), be.state());
                if (!m.master().designateTracked(far.getUUID(), null)) {
                    if (t > 250) h.fail("monitor does not see the far husk");
                    return;
                }
                designatedAt[0] = t;
                return;
            }
            if (t - designatedAt[0] == 30 && w.rocketId < 0) h.fail("nose did not launch on the designated target: " + be.state());
            if (w.lock != null && !w.lock.equals(far.getUUID().toString())) h.fail("rocket target is not the designated one: " + w.lock);
            if (w.min < HIT) {
                FcsMod.LOG.info("[radar_fcs test] rocket_net_des: hit the designated target, closest {}", f(w.min));
                h.succeed();
            } else if (t - designatedAt[0] > 220) {
                h.fail("rocket missed the designated target: closest " + f(w.min));
            }
        });
    }

    /** Ракеты сети: когда каждая появилась и когда пропала (тики теста). */
    static final class Launches {
        final java.util.Map<Integer, int[]> seen = new java.util.LinkedHashMap<>();

        void tick(ServerLevel level, int t) {
            java.util.Set<Integer> alive = new java.util.HashSet<>();
            for (RocketGuidance.Guided g : RocketGuidance.guided(level)) {
                if (g.rocket().isRemoved()) continue;
                alive.add(g.rocket().getId());
                seen.computeIfAbsent(g.rocket().getId(), k -> new int[]{t, -1});
            }
            for (var e : seen.entrySet()) if (e.getValue()[1] < 0 && !alive.contains(e.getKey())) e.getValue()[1] = t;
        }

        List<int[]> list() {
            return new java.util.ArrayList<>(seen.values());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int[] v : seen.values()) sb.append(" [").append(v[0]).append("..").append(v[1]).append(']');
            return sb.toString();
        }
    }

    /** Сеть с вычислителем (автопушка) и программируемыми носами на автопуске на площадках pads; цель - один моб. */
    private static List<NetNoseBlockEntity> salvoSetup(GameTestHelper h, java.util.function.Consumer<dev.radarfcs.core.EngageSettings> cfg,
                                                       BlockPos... pads) {
        FcsGameTests.floor(h);
        FcsGameTests.forceArea(h, new BlockPos(0, 0, -40), new BlockPos(160, 0, 70));
        FcsGameTests.removeBarriers(h, new BlockPos(-4, 1, -40), new BlockPos(160, 60, 70));
        BlockPos fcsPos = new BlockPos(6, 1, 50), mount = new BlockPos(10, 1, 50);
        FcsGameTests.autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity fcs = FcsGameTests.fcs(h, fcsPos);
        SettingsGameTests.configure(fcs, st -> {
            st.dropStatic = false;
            cfg.accept(st);
        });
        UUID net = FcsGameTests.networkAt(h, fcsPos);
        List<NetNoseBlockEntity> out = new java.util.ArrayList<>();
        for (BlockPos pad : pads) {
            BlockPos nose = rocket(h, pad, Direction.EAST, RocketNoses.NET.get());
            if (h.getLevel().getBlockEntity(h.absolutePos(nose)) instanceof NetNoseBlockEntity be) {
                be.setNetwork(net);
                be.apply(NetNoseBlockEntity.Mode.AUTO, -1);
                out.add(be);
            }
        }
        return out;
    }

    /**
     * Ракеты вычислителя «одиночные»: два носа на автопуске, одна цель (живучий моб) - второй ждёт («ждёт»), пока
     * первая ракета летит, и уходит только после неё.
     */
    @GameTest(template = ARENA, batch = "rocket_salvo_single", timeoutTicks = 500)
    public static void rocketSalvoSingleOnePerTarget(GameTestHelper h) {
        if (!Mods.ENHANCED_SHELLS) {
            h.succeed();
            return;
        }
        List<NetNoseBlockEntity> noses = salvoSetup(h, st -> {
            st.rocketMode = dev.radarfcs.core.EngageSettings.RocketMode.SINGLE;
            st.rocketIntervalTicks = 10;
        }, new BlockPos(6, 16, 20), new BlockPos(6, 16, 14));
        Mob husk = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(100.5, 14, 30.5), 0, 0));
        if (noses.size() != 2) {
            h.fail("noses not set up");
            return;
        }
        Launches l = new Launches();
        boolean[] held = {false};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            l.tick(h.getLevel(), t);
            List<int[]> r = l.list();
            if (r.size() == 1 && r.get(0)[1] < 0) {
                for (NetNoseBlockEntity n : noses) if (!n.isRemoved() && n.state() == NetNoseBlockEntity.State.HOLD) held[0] = true;
            }
            if (t % 40 == 0) FcsMod.LOG.info("[radar_fcs test] rocket_single t {} rockets{} husk alive {}", t, l, husk.isAlive());
            if (r.size() >= 2) {
                int[] a = r.get(0), b = r.get(1);
                if (a[1] < 0 || b[0] < a[1]) h.fail("second rocket launched while the first was still flying:" + l);
                if (!held[0]) h.fail("second nose was not in HOLD while the first rocket flew");
                FcsMod.LOG.info("[radar_fcs test] rocket_single: one rocket at a time:{}", l);
                h.succeed();
            } else if (t > 460) {
                h.fail("expected two rockets one after another:" + l + ", husk alive " + husk.isAlive());
            }
        });
    }

    /**
     * Ракеты вычислителя «очередь» из 2, задержка между ракетами 1 с, пауза между очередями 0.5 с: три носа - два
     * уходят подряд (через 20-30 тиков один после другого), третий ждёт, пока они летят, и уходит новой очередью, когда
     * они долетели.
     */
    @GameTest(template = ARENA, batch = "rocket_salvo_queue", timeoutTicks = 500)
    public static void rocketSalvoQueueOfTwo(GameTestHelper h) {
        if (!Mods.ENHANCED_SHELLS) {
            h.succeed();
            return;
        }
        List<NetNoseBlockEntity> noses = salvoSetup(h, st -> {
            st.rocketMode = dev.radarfcs.core.EngageSettings.RocketMode.QUEUE;
            st.rocketQueue = 2;
            st.rocketQueueDelayTicks = 20;
            st.rocketIntervalTicks = 10;
        }, new BlockPos(6, 16, 20), new BlockPos(6, 16, 14), new BlockPos(6, 16, 26));
        Mob husk = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(100.5, 14, 30.5), 0, 0));
        if (noses.size() != 3) {
            h.fail("noses not set up");
            return;
        }
        Launches l = new Launches();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            l.tick(h.getLevel(), t);
            List<int[]> r = l.list();
            if (t % 40 == 0) FcsMod.LOG.info("[radar_fcs test] rocket_queue t {} rockets{} husk alive {}", t, l, husk.isAlive());
            if (r.size() >= 2) {
                int gap = r.get(1)[0] - r.get(0)[0];
                if (gap < 20) h.fail("delay between rockets of the queue below 1 s:" + l);
                if (gap > 35) h.fail("second rocket of the queue came too late:" + l);
            }
            if (r.size() >= 3) {
                int[] third = r.get(2);
                if (r.get(0)[1] < 0 || r.get(1)[1] < 0 || third[0] < Math.max(r.get(0)[1], r.get(1)[1])) {
                    h.fail("third rocket launched before the queue of two was done:" + l);
                }
                FcsMod.LOG.info("[radar_fcs test] rocket_queue: queue of two, then a new queue:{}", l);
                h.succeed();
            } else if (t > 460) {
                h.fail("expected a queue of two and then a third rocket:" + l + ", husk alive " + husk.isAlive());
            }
        });
    }

    /**
     * Топливо кончилось - ракета падает: тепловая головка с одним зарядом (30 тиков тяги) захватывает моба в ~95 блоках
     * в стороне от оси, но выгорает раньше. После выгорания ракета сразу выходит из наведения, курс больше не меняется
     * (ракета не доворачивает и не наматывает дуги), она снижается по баллистике.
     */
    @GameTest(template = ARENA, batch = "rocket_burnout", timeoutTicks = 300)
    public static void rocketWithoutFuelFalls(GameTestHelper h) {
        if (!Mods.ENHANCED_SHELLS) {
            h.succeed();
            return;
        }
        FcsGameTests.floor(h);
        FcsGameTests.forceArea(h, new BlockPos(0, 0, -40), new BlockPos(160, 0, 70));
        FcsGameTests.removeBarriers(h, new BlockPos(-4, 1, -40), new BlockPos(160, 60, 70));
        rocket(h, new BlockPos(6, 40, 20), Direction.EAST, RocketNoses.HEAT.get(), 1);       // высоко: без тяги ракета проседает
        Mob husk = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(100.5, 30, 34.5), 0, 0));
        com.example.cbcenhancedshells.rocket.RocketEntity[] rocket = {null};
        int[] burnAt = {-1};
        double[] heading = {Double.NaN};
        boolean[] fell = {false}, locked = {false};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            ServerLevel level = h.getLevel();
            if (t == 10) h.setBlock(new BlockPos(6, 40, 19), Blocks.REDSTONE_BLOCK.defaultBlockState());
            if (rocket[0] == null) for (RocketGuidance.Guided g : RocketGuidance.guided(level)) {
                if (g.rocket() instanceof com.example.cbcenhancedshells.rocket.RocketEntity re) rocket[0] = re;
            }
            if (rocket[0] == null) {
                if (t > 14) h.fail("rocket did not launch");
                return;
            }
            var e = rocket[0];
            for (RocketGuidance.Guided g : RocketGuidance.guided(level)) if (g.rocket() == e && g.lock() != null) locked[0] = true;
            boolean guided = RocketGuidance.guided(level).stream().anyMatch(g -> g.rocket() == e);
            if (burnAt[0] < 0) {
                if (e.isRemoved()) h.fail("rocket ended before it burned out (closest to the husk " + f(e.position().distanceTo(husk.position())) + ")");
                if (e.getThrustRemaining() <= 0) burnAt[0] = t;
                return;
            }
            int k = t - burnAt[0];
            if (k == 0 && !locked[0]) h.fail("heat seeker locked nothing before burnout");
            if (k >= 2 && guided) h.fail("burned-out rocket is still guided");
            Vec3 v = e.getDeltaMovement();
            double hd = Math.toDegrees(Math.atan2(v.z, v.x));
            if (k == 3) heading[0] = hd;
            if (k > 3 && !e.isRemoved() && Math.abs(hd - heading[0]) > 0.5) {
                h.fail("burned-out rocket still turns: heading " + f(heading[0]) + " -> " + f(hd) + " after " + k + " ticks");
            }
            if (k > 3 && v.y < -0.05) fell[0] = true;
            if (k % 10 == 0) FcsMod.LOG.info("[radar_fcs test] rocket_burnout k {} pos {} vel {} guided {}", k, e.position(), v, guided);
            if (e.isRemoved() || k >= 40) {
                if (k < 4) h.fail("rocket ended right after burnout, nothing checked");
                if (!fell[0] && !e.isRemoved()) h.fail("burned-out rocket is not falling: vel " + v);
                FcsMod.LOG.info("[radar_fcs test] rocket_burnout: falls after burnout at tick {}, course kept {} ticks", burnAt[0], k);
                h.succeed();
            }
        });
    }

    /**
     * Сброс очереди: очередь из 3, а ракета на площадке одна - ушла одна, ракеты кончились. Площадки перезарядили (3
     * ракеты), когда первая долетела: новая очередь - снова полная, все 3 подряд (а не 2 «оставшиеся» прошлой очереди и
     * третья только после них).
     */
    @GameTest(template = ARENA, batch = "rocket_salvo_reset", timeoutTicks = 600)
    public static void rocketSalvoQueueResetsAfterRunningOut(GameTestHelper h) {
        if (!Mods.ENHANCED_SHELLS) {
            h.succeed();
            return;
        }
        List<NetNoseBlockEntity> noses = salvoSetup(h, st -> {
            st.rocketMode = dev.radarfcs.core.EngageSettings.RocketMode.QUEUE;
            st.rocketQueue = 3;
            st.rocketQueueDelayTicks = 10;
            st.rocketIntervalTicks = 10;
        }, new BlockPos(6, 16, 20));
        Mob husk = SettingsGameTests.tough(FcsGameTests.movingTarget(h, EntityType.HUSK, new Vec3(100.5, 14, 30.5), 0, 0));
        if (noses.size() != 1 || noses.get(0).network() == null) {
            h.fail("nose not set up");
            return;
        }
        UUID net = noses.get(0).network();
        BlockPos[] pads = {new BlockPos(6, 16, 20), new BlockPos(6, 16, 14), new BlockPos(6, 16, 26)};
        Launches l = new Launches();
        int[] reloadAt = {-1};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            l.tick(h.getLevel(), t);
            List<int[]> r = l.list();
            if (t % 40 == 0) FcsMod.LOG.info("[radar_fcs test] rocket_reset t {} rockets{} husk alive {}", t, l, husk.isAlive());
            if (reloadAt[0] < 0 && r.size() > 1) h.fail("more than one rocket before the reload:" + l);
            // ракеты кончились; первая долетела - перезарядка: 3 ракеты на площадках
            if (reloadAt[0] < 0 && r.size() == 1 && r.get(0)[1] >= 0 && t >= r.get(0)[0] + 50) {
                reloadAt[0] = t;
                for (BlockPos pad : pads) {
                    BlockPos nose = rocket(h, pad, Direction.EAST, RocketNoses.NET.get());
                    if (h.getLevel().getBlockEntity(h.absolutePos(nose)) instanceof NetNoseBlockEntity be) {
                        be.setNetwork(net);
                        be.apply(NetNoseBlockEntity.Mode.AUTO, -1);
                    }
                }
            }
            if (r.size() >= 4) {
                int[] a = r.get(1), c = r.get(3);
                if (c[0] - a[0] > 40 || (a[1] >= 0 && c[0] >= a[1])) {
                    h.fail("queue after the reload is not a full one (third rocket waited for the first ones):" + l);
                }
                FcsMod.LOG.info("[radar_fcs test] rocket_reset: one rocket, reload, then a full queue of three:{}", l);
                h.succeed();
            } else if (t > 560) {
                h.fail("expected one rocket and then a full queue of three:" + l + ", reload at " + reloadAt[0]);
            }
        });
    }
}
