package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.countRounds;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.forceArea;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.removeBarriers;
import static dev.radarfcs.gametest.SettingsGameTests.configure;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.compat.CbcBallistics;
import dev.radarfcs.compat.Munitions;
import dev.radarfcs.core.BallisticSolver;
import dev.radarfcs.core.CbcFlightModel;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.ProjectileType;
import dev.radarfcs.core.Track;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.iff.TransponderMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Перехват снарядов: вычислитель сбивает летящие снаряды больших пушек (с модом CBC Interception), берёт только
 * угрожающие (фильтр угрозы), не трогает свои (выпущенные своей пушкой или из своей зоны) и не отслеживает мелкие
 * снаряды. Батарея: автопушка (4 ствола, пулемётные патроны) на станке (10,1,32), вычислитель (6,1,28).
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class InterceptGameTests {
    private static final String ARENA = "arena";
    private static final BlockPos MOUNT = new BlockPos(10, 1, 32);
    private static final BlockPos FCS = new BlockPos(6, 1, 28);

    static void setup(ServerLevel l) {
        FcsGameTests.direct(l, 200);
        // без CBC Interception снаряды пролетают друг сквозь друга, но выбор целей и огонь проверяем и без него
        FcsConfig.INTERCEPT_REQUIRES_MOD.set(false);
    }

    static void teardown(ServerLevel l) {
        FcsConfig.INTERCEPT_REQUIRES_MOD.set(true);
        FcsGameTests.cleanup(l);
    }

    @BeforeBatch(batch = "intercept") public static void b1(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "intercept") public static void a1(ServerLevel l) { teardown(l); }
    @BeforeBatch(batch = "intercept_threat") public static void b2(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "intercept_threat") public static void a2(ServerLevel l) { teardown(l); }
    @BeforeBatch(batch = "intercept_zone") public static void b3(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "intercept_zone") public static void a3(ServerLevel l) { teardown(l); }
    @BeforeBatch(batch = "intercept_own") public static void b4(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "intercept_own") public static void a4(ServerLevel l) { teardown(l); }
    @BeforeBatch(batch = "intercept_small") public static void b5(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "intercept_small") public static void a5(ServerLevel l) { teardown(l); }
    @BeforeBatch(batch = "intercept_fast") public static void b6(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "intercept_fast") public static void a6(ServerLevel l) { teardown(l); }
    @BeforeBatch(batch = "intercept_rocket") public static void b7(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "intercept_rocket") public static void a7(ServerLevel l) { teardown(l); }
    @BeforeBatch(batch = "intercept_nomod") public static void b8(ServerLevel l) { FcsGameTests.direct(l, 200); }
    @BeforeBatch(batch = "intercept_radar") public static void b9(ServerLevel l) {
        setup(l);
        dev.radarfcs.FcsDev.radarless = false;                     // цели - только с радара Create Radars
    }
    @AfterBatch(batch = "intercept_radar") public static void a9(ServerLevel l) { teardown(l); }
    @AfterBatch(batch = "intercept_nomod") public static void a8(ServerLevel l) { teardown(l); }

    // ------------------------------------------------------------------ помощники

    /** Батарея: автопушка и вычислитель, зона полёта снарядов открыта (барьеры убраны, чанки загружены). */
    static FireControlBlockEntity battery(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 512);
        FireControlBlockEntity be = fcs(h, FCS);
        removeBarriers(h, new BlockPos(-4, 1, -60), new BlockPos(140, 70, 120));
        forceArea(h, new BlockPos(-16, 0, -70), new BlockPos(150, 0, 130));
        return be;
    }

    /**
     * Выпустить снаряд CBC (typeId) из точки from так, чтобы он упал в точку to (координаты площадки): угол
     * возвышения - по физике самого снаряда (сопротивление, гравитация), скорость speed блоков/тик.
     */
    static AbstractCannonProjectile launch(GameTestHelper h, String typeId, Vec3 fromRel, Vec3 toRel, double speed) {
        ServerLevel l = h.getLevel();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(typeId));
        Entity e = type.create(l);
        if (!(e instanceof AbstractCannonProjectile p)) throw new GameTestAssertException("not a CBC projectile: " + typeId);
        // ракеты CBC-AT: без запаса хода исчезают сразу, без топлива летят без тяги
        invoke(p, "setLifetime", 200);
        invoke(p, "setFuel", 12);
        Vec3 from = h.absoluteVec(fromRel), to = h.absoluteVec(toRel);
        CbcFlightModel m = CbcBallistics.fromProjectile(l, p, speed, 400, CbcFlightModel.ballistic(speed, -0.05, 0.01, false, 400));
        Vec3 d = to.subtract(from);
        double horiz = Math.hypot(d.x, d.z);
        double[] sol = new BallisticSolver(-60, 60, false).solvePitch(horiz, d.y, m, Double.NaN);
        if (sol == null) throw new GameTestAssertException("cannot reach " + toRel + " from " + fromRel);
        double c = Math.cos(sol[0]) * speed / horiz;
        p.setPos(from);
        p.setDeltaMovement(new Vec3(d.x * c, Math.sin(sol[0]) * speed, d.z * c));
        l.addFreshEntity(p);
        return p;
    }

    private static void invoke(Object o, String name, int value) {
        try {
            o.getClass().getMethod(name, int.class).invoke(o, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // у снаряда нет такого свойства
        }
    }

    /** Чем кончился полёт снаряда: сбит в воздухе (исчез далеко от батареи и выше земли) или упал. */
    record Outcome(AtomicInteger intercepted, AtomicInteger landed) {}

    static Outcome watch(GameTestHelper h, List<AbstractCannonProjectile> shells) {
        Outcome o = new Outcome(new AtomicInteger(), new AtomicInteger());
        Map<AbstractCannonProjectile, Vec3> last = new HashMap<>();
        Vec3 battery = h.absoluteVec(Vec3.atCenterOf(MOUNT));
        double groundY = h.absoluteVec(Vec3.ZERO).y;
        h.onEachTick(() -> {
            for (AbstractCannonProjectile p : List.copyOf(shells)) {
                boolean gone = p.isRemoved() || p.isInGround();
                if (!gone) {
                    last.put(p, p.position());
                    continue;
                }
                shells.remove(p);
                Vec3 at = last.getOrDefault(p, p.position());
                boolean air = !p.isInGround() && at.y > groundY + 3 && at.distanceTo(battery) > 6;
                (air ? o.intercepted : o.landed).incrementAndGet();
                FcsMod.LOG.info("[radar_fcs test] shell {} ended {} at {} ({} blocks from the battery)", p.getId(),
                        air ? "IN THE AIR" : "on the ground", BlockPos.containing(at.subtract(h.absoluteVec(Vec3.ZERO))),
                        Math.round(at.distanceTo(battery)));
            }
        });
        return o;
    }

    /** Активные снаряды-цели вычислителя (сопровождаются как цель). */
    static long activeShells(FireControlBlockEntity be) {
        return be.sensor().active().stream().filter(t -> t.kind == Track.Kind.PROJECTILE).count();
    }

    // ------------------------------------------------------------------ тесты

    /**
     * Перехват: болванки летят на батарею с востока (100 блоков, 5 бл/тик, по баллистической дуге) и падают в 10 блоках
     * перед пушкой. Вычислитель сопровождает их, автопушка сбивает в воздухе (с CBC Interception). Без мода
     * перехвата проверяется только, что вычислитель берёт снаряды целью и стреляет.
     */
    @GameTest(template = ARENA, batch = "intercept", timeoutTicks = 420)
    public static void interceptsIncomingShells(GameTestHelper h) {
        FireControlBlockEntity be = battery(h);
        boolean mod = ModList.get().isLoaded("cbcintercept");
        List<AbstractCannonProjectile> shells = new ArrayList<>();
        AtomicInteger launched = new AtomicInteger(), sawTarget = new AtomicInteger();
        Outcome o = watch(h, shells);
        AtomicInteger rounds = countRounds(h, MOUNT);
        logEvery(h, be, "intercept", 5);
        for (int i = 0; i < 6; i++) {
            int k = i;
            h.runAtTickTime(20 + 50 * i, () -> {
                // попеременно: прямо на пушку и с поправкой вбок (наводка поворачивается)
                Vec3 from = new Vec3(110.5, 24, 32.5 + (k % 2 == 0 ? 0 : 30));
                shells.add(launch(h, "createbigcannons:shot", from, new Vec3(20.5, 1, 32.5), 5.0));
                launched.incrementAndGet();
            });
        }
        h.onEachTick(() -> { if (activeShells(be) > 0) sawTarget.incrementAndGet(); });
        h.runAtTickTime(400, () -> {
            FcsMod.LOG.info("[radar_fcs test] intercept: {} shells, {} shot down in the air, {} landed, {} rounds fired, interception mod {}",
                    launched.get(), o.intercepted().get(), o.landed().get(), rounds.get(), mod ? "on" : "absent");
            if (sawTarget.get() == 0) h.fail("incoming shells never became targets");
            if (rounds.get() == 0) h.fail("no rounds fired at incoming shells");
            if (mod && o.intercepted().get() < 4) h.fail("only " + o.intercepted().get() + " of " + launched.get() + " shells shot down");
            h.succeed();
        });
    }

    /**
     * Быстрые снаряды по косой: болванки 8 бл/тик (160 м/с) с северо-востока, с 130 блоков, падают у вычислителя -
     * автопушке приходится разворачиваться на каждую.
     */
    @GameTest(template = ARENA, batch = "intercept_fast", timeoutTicks = 380)
    public static void interceptsFastCrossingShells(GameTestHelper h) {
        FireControlBlockEntity be = battery(h);
        boolean mod = ModList.get().isLoaded("cbcintercept");
        List<AbstractCannonProjectile> shells = new ArrayList<>();
        AtomicInteger launched = new AtomicInteger();
        Outcome o = watch(h, shells);
        AtomicInteger rounds = countRounds(h, MOUNT);
        logEvery(h, be, "intercept_fast", 5);
        Vec3[] from = {new Vec3(100.5, 40, -55.5), new Vec3(120.5, 30, 100.5), new Vec3(130.5, 50, 20.5)};
        for (int i = 0; i < 6; i++) {
            int k = i;
            h.runAtTickTime(20 + 50 * i, () -> {
                shells.add(launch(h, "createbigcannons:shot", from[k % from.length], new Vec3(9.5, 1, 26.5), 8.0));
                launched.incrementAndGet();
            });
        }
        h.runAtTickTime(360, () -> {
            FcsMod.LOG.info("[radar_fcs test] intercept_fast: {} shells at 160 m/s, {} shot down in the air, {} landed, {} rounds fired",
                    launched.get(), o.intercepted().get(), o.landed().get(), rounds.get());
            if (rounds.get() == 0) h.fail("no rounds fired at fast shells");
            if (mod && o.intercepted().get() < 4) h.fail("only " + o.intercepted().get() + " of " + launched.get() + " fast shells shot down");
            h.succeed();
        });
    }

    /**
     * С настоящим радаром: плоский радар Create Radars на линке вычислителя видит летящие болванки (категория
     * снарядов CR), вычислитель берёт их с радара и сбивает.
     */
    @GameTest(template = ARENA, batch = "intercept_radar", timeoutTicks = 380)
    public static void interceptsWithCreateRadar(GameTestHelper h) {
        if (!ModList.get().isLoaded("create_radar")) {
            h.succeed();
            return;
        }
        FireControlBlockEntity be = battery(h);
        // плоский радар сканирует в сторону, противоположную FACING: FACING=запад -> луч на восток, навстречу снарядам
        BlockPos radar = new BlockPos(8, 1, 26);
        h.setBlock(radar, FcsGameTests.block("create_radar:plane_radar").defaultBlockState()
                .trySetValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        FcsGameTests.CREATED.add(h.absolutePos(radar));
        FcsGameTests.link(h, radar, Direction.UP, FCS);
        boolean mod = ModList.get().isLoaded("cbcintercept");
        List<AbstractCannonProjectile> shells = new ArrayList<>();
        AtomicInteger launched = new AtomicInteger(), tracked = new AtomicInteger();
        Outcome o = watch(h, shells);
        AtomicInteger rounds = countRounds(h, MOUNT);
        logEvery(h, be, "intercept_radar", 10);
        for (int i = 0; i < 5; i++) {
            h.runAtTickTime(40 + 60 * i, () -> {
                shells.add(launch(h, "createbigcannons:shot", new Vec3(100.5, 20, 28.5), new Vec3(20.5, 1, 30.5), 4.0));
                launched.incrementAndGet();
            });
        }
        Map<String, Integer> staleTicks = new HashMap<>();
        h.onEachTick(() -> {
            for (var t : be.sensor().all()) {
                if (t.kind != Track.Kind.PROJECTILE) continue;
                tracked.incrementAndGet();
                // сбитый снаряд радар ещё помнит: по его старой отметке трека быть не должно (снаряд, сбитый в конце
                // тика, снимается при следующем опросе радара)
                if (!(t.handle instanceof Entity e)) throw new GameTestAssertException("ghost projectile track " + t.id);
                if (e.isRemoved() && staleTicks.merge(t.id, 1, Integer::sum) > 2) {
                    throw new GameTestAssertException("track of a destroyed shell kept: " + t.id);
                }
            }
        });
        h.runAtTickTime(360, () -> {
            FcsMod.LOG.info("[radar_fcs test] intercept_radar: {} shells, radar used {}, tracked {} ticks, {} shot down, {} landed, {} rounds",
                    launched.get(), be.sensor().usedRadar(), tracked.get(), o.intercepted().get(), o.landed().get(), rounds.get());
            if (!be.sensor().usedRadar()) h.fail("radar not used");
            if (tracked.get() == 0) h.fail("Create Radars did not report flying shells");
            if (rounds.get() == 0) h.fail("no rounds fired at radar-tracked shells");
            if (mod && o.intercepted().get() < 3) h.fail("only " + o.intercepted().get() + " of " + launched.get() + " shells shot down");
            h.succeed();
        });
    }

    /**
     * Ракеты CBC Advanced Technologies (средние): полёт прогнозируется по замерам (тяга), автопушка их сбивает.
     * Без CBC-AT тест не применим.
     */
    @GameTest(template = ARENA, batch = "intercept_rocket", timeoutTicks = 380)
    public static void interceptsRockets(GameTestHelper h) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("cbc_at:medium_he_rocket")).orElse(null);
        if (type == null) {
            FcsMod.LOG.info("[radar_fcs test] intercept_rocket: CBC Advanced Technologies not installed, test skipped");
            h.succeed();
            return;
        }
        FireControlBlockEntity be = battery(h);
        configure(be, s -> s.threat = EngageSettings.Threat.ALL);       // разгон тягой уводит ракету выше точки прицела
        boolean mod = ModList.get().isLoaded("cbcintercept");
        List<AbstractCannonProjectile> rockets = new ArrayList<>();
        AtomicInteger launched = new AtomicInteger(), tracked = new AtomicInteger();
        Outcome o = watch(h, rockets);
        AtomicInteger rounds = countRounds(h, MOUNT);
        logEvery(h, be, "intercept_rocket", 5);
        for (int i = 0; i < 5; i++) {
            int k = i;
            h.runAtTickTime(20 + 60 * i, () -> {
                rockets.add(launch(h, "cbc_at:medium_he_rocket", new Vec3(110.5, 20, 32.5 + (k % 2 == 0 ? -20 : 20)),
                        new Vec3(18.5, 1, 32.5), 4.0));
                launched.incrementAndGet();
            });
        }
        h.onEachTick(() -> {
            for (var t : be.sensor().all()) if (t.kind == Track.Kind.PROJECTILE && t.munition == ProjectileType.ROCKET) tracked.incrementAndGet();
        });
        h.runAtTickTime(360, () -> {
            FcsMod.LOG.info("[radar_fcs test] intercept_rocket: {} rockets, tracked {} ticks, {} shot down in the air, {} landed, {} rounds",
                    launched.get(), tracked.get(), o.intercepted().get(), o.landed().get(), rounds.get());
            if (tracked.get() == 0) h.fail("rockets were not tracked as rockets");
            if (rounds.get() == 0) h.fail("no rounds fired at rockets");
            if (mod && o.intercepted().get() < 3) h.fail("only " + o.intercepted().get() + " of " + launched.get() + " rockets shot down");
            h.succeed();
        });
    }

    /**
     * Без CBC Interception (и requireInterceptionMod = true) снаряды видны, но огня по ним нет, в сводке - «нет мода».
     * С модом - в сводке перехват включён.
     */
    @GameTest(template = ARENA, batch = "intercept_nomod", timeoutTicks = 160)
    public static void noFireWithoutInterceptionMod(GameTestHelper h) {
        FireControlBlockEntity be = battery(h);
        boolean mod = ModList.get().isLoaded("cbcintercept");
        AtomicInteger rounds = countRounds(h, MOUNT), seen = new AtomicInteger();
        h.runAtTickTime(10, () -> launch(h, "createbigcannons:shot", new Vec3(110.5, 24, 32.5), new Vec3(20.5, 1, 32.5), 5.0));
        h.onEachTick(() -> {
            for (var t : be.sensor().all()) if (t.kind == Track.Kind.PROJECTILE) seen.incrementAndGet();
        });
        h.runAtTickTime(140, () -> {
            if (seen.get() == 0) h.fail("shell not tracked");
            boolean flag = be.summary().getBoolean("Intercept");
            if (flag != mod) h.fail("summary interception flag " + flag + ", mod " + mod);
            if (!mod && rounds.get() > 0) h.fail(rounds.get() + " rounds fired without the interception mod");
            if (mod && rounds.get() == 0) h.fail("no rounds fired with the interception mod");
            h.succeed();
        });
    }

    /**
     * Фильтр угрозы: болванки падают в 45 блоках к северу от батареи - «угрожающие своим» (радиус 16) их не берут,
     * огня нет; режим «все чужие» - берёт и стреляет.
     */
    @GameTest(template = ARENA, batch = "intercept_threat", timeoutTicks = 360)
    public static void interceptsOnlyThreats(GameTestHelper h) {
        FireControlBlockEntity be = battery(h);
        List<AbstractCannonProjectile> shells = new ArrayList<>();
        AtomicInteger rounds = countRounds(h, MOUNT), wrong = new AtomicInteger(), engagedAll = new AtomicInteger();
        AtomicInteger phase = new AtomicInteger();
        logEvery(h, be, "intercept_threat", 10);
        Vec3 aside = new Vec3(20.5, 1, -12.5);
        for (int i = 0; i < 2; i++) h.runAtTickTime(20 + 50 * i, () -> shells.add(launch(h, "createbigcannons:shot",
                new Vec3(110.5, 24, -12.5), aside, 5.0)));
        h.onEachTick(() -> {
            long a = activeShells(be);
            if (phase.get() == 0 && a > 0) wrong.incrementAndGet();
            if (phase.get() == 1 && a > 0) engagedAll.incrementAndGet();
            for (var t : be.sensor().all()) {
                if (phase.get() == 0 && t.kind == Track.Kind.PROJECTILE && t.threatDistance < 16) {
                    throw new GameTestAssertException("shell landing 45 blocks away judged as a threat: " + t.threatDistance);
                }
            }
        });
        h.runAtTickTime(160, () -> {
            if (wrong.get() > 0 || rounds.get() > 0) h.fail("non-threatening shells engaged: " + wrong.get() + " ticks, " + rounds.get() + " rounds");
            phase.set(1);
            configure(be, s -> s.threat = EngageSettings.Threat.ALL);
        });
        for (int i = 0; i < 2; i++) h.runAtTickTime(170 + 50 * i, () -> shells.add(launch(h, "createbigcannons:shot",
                new Vec3(110.5, 24, -12.5), aside, 5.0)));
        h.runAtTickTime(340, () -> {
            FcsMod.LOG.info("[radar_fcs test] intercept_threat: all-hostile mode engaged for {} ticks, {} rounds", engagedAll.get(), rounds.get());
            if (engagedAll.get() == 0) h.fail("all-hostile mode did not engage passing shells");
            h.succeed();
        });
    }

    /** Снаряды, выпущенные из своей зоны транспондера (база противника была бы чужой), - свои: огня нет. */
    @GameTest(template = ARENA, batch = "intercept_zone", timeoutTicks = 200)
    public static void shellsFromOwnZoneAreFriendly(GameTestHelper h) {
        FireControlBlockEntity be = battery(h);
        IffGameTests.transponder(h, new BlockPos(110, 1, 32), "", TransponderMode.RADIUS, 30);
        List<AbstractCannonProjectile> shells = new ArrayList<>();
        AtomicInteger rounds = countRounds(h, MOUNT), seen = new AtomicInteger();
        for (int i = 0; i < 2; i++) h.runAtTickTime(20 + 50 * i, () -> shells.add(launch(h, "createbigcannons:shot",
                new Vec3(110.5, 24, 32.5), new Vec3(20.5, 1, 32.5), 5.0)));
        h.onEachTick(() -> {
            for (var t : be.sensor().all()) {
                if (t.kind != Track.Kind.PROJECTILE) continue;
                seen.incrementAndGet();
                if (!t.friendly || t.active) throw new GameTestAssertException("shell from a friendly zone is a target");
            }
        });
        h.runAtTickTime(180, () -> {
            if (seen.get() == 0) h.fail("shells were not tracked at all");
            if (rounds.get() > 0) h.fail(rounds.get() + " rounds fired at friendly shells");
            h.succeed();
        });
    }

    /**
     * Свои снаряды: большая пушка вычислителя стреляет по цели, автопушка того же вычислителя с режимом «все чужие» -
     * её снаряд видит, но своим (выпущен своей пушкой) и не берёт.
     */
    @GameTest(template = ARENA, batch = "intercept_own", timeoutTicks = 300)
    public static void ownShellsAreFriendly(GameTestHelper h) {
        floor(h);
        FcsGameTests.bigCannon(h, new BlockPos(10, 1, 20), Direction.EAST, 2, 6, true);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        removeBarriers(h, new BlockPos(-4, 1, -20), new BlockPos(140, 70, 80));
        forceArea(h, new BlockPos(-16, 0, -30), new BlockPos(150, 0, 90));
        configure(be, s -> s.threat = EngageSettings.Threat.ALL);
        var husk = FcsGameTests.linearTarget(h, EntityType.HUSK, new Vec3(58.5, 1, 14.5), new Vec3(0, 0, 0.1), 40);
        var hp = husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1024);
        husk.setHealth(husk.getMaxHealth());
        logEvery(h, be, "intercept_own", 10);
        AtomicInteger seen = new AtomicInteger();
        h.onEachTick(() -> {
            for (var t : be.sensor().all()) {
                if (t.kind != Track.Kind.PROJECTILE) continue;
                seen.incrementAndGet();
                if (!t.friendly) throw new GameTestAssertException("own big cannon shell is not friendly");
                for (MountState m : be.mountStates()) {
                    if (t.id.equals(m.targetId)) throw new GameTestAssertException("own shell picked as a target");
                }
            }
        });
        h.runAtTickTime(280, () -> {
            if (seen.get() == 0) h.fail("own shell never tracked (big cannon did not fire?)");
            FcsMod.LOG.info("[radar_fcs test] intercept_own: own shell tracked {} ticks as friendly", seen.get());
            h.succeed();
        });
    }

    /**
     * Мелкие снаряды не отслеживаются: патроны автопушек и пулемётов, летящие на батарею, вычислитель не видит;
     * снаряды больших пушек и ракеты - распознаются.
     */
    @GameTest(template = ARENA, batch = "intercept_small", timeoutTicks = 120)
    public static void smallProjectilesNotTracked(GameTestHelper h) {
        FireControlBlockEntity be = battery(h);
        ServerLevel l = h.getLevel();
        Map<String, ProjectileType> expected = new HashMap<>();
        expected.put("createbigcannons:shot", ProjectileType.SHELL);
        expected.put("createbigcannons:he_shell", ProjectileType.SHELL);
        expected.put("createbigcannons:ap_shot", ProjectileType.SHELL);
        expected.put("createbigcannons:shrapnel_shell", ProjectileType.SHELL);
        expected.put("createbigcannons:machine_gun_bullet", null);
        expected.put("createbigcannons:ap_autocannon", null);
        expected.put("createbigcannons:flak_autocannon", null);
        StringBuilder log = new StringBuilder();
        for (var en : expected.entrySet()) {
            Entity e = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(en.getKey())).create(l);
            ProjectileType got = e == null ? null : Munitions.classify(e);
            if (got != en.getValue()) h.fail(en.getKey() + " classified as " + got + ", expected " + en.getValue());
        }
        // аддоны: ракеты ракетных блоков CBC-AT - мелкие, средние ракеты - ракеты; тяжёлые автопушки - мелкие
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            if (!id.startsWith("cbc_at:") && !id.startsWith("cbcatfix:") && !id.startsWith("cbcmodernwarfare:")) continue;
            Entity e;
            try {
                e = type.create(l);
            } catch (RuntimeException ex) {
                continue;
            }
            if (e == null) continue;
            ProjectileType got = Munitions.classify(e);
            ProjectileType want = expectedByClass(e.getClass());
            log.append(' ').append(id).append('=').append(got);
            if (got != want) h.fail(id + " (" + e.getClass().getSimpleName() + ") classified as " + got + ", expected " + want);
        }
        FcsMod.LOG.info("[radar_fcs test] intercept_small: addon projectiles:{}", log);
        // пулемётные пули летят на батарею: не треки
        for (int i = 0; i < 6; i++) {
            Entity e = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("createbigcannons:machine_gun_bullet")).create(l);
            if (e == null) continue;
            e.setPos(h.absoluteVec(new Vec3(60.5, 6 + i, 32.5)));
            e.setDeltaMovement(new Vec3(-2.5, 0, 0));
            l.addFreshEntity(e);
        }
        h.runAtTickTime(10, () -> {
            for (var t : be.sensor().all()) {
                if (t.kind == Track.Kind.PROJECTILE) h.fail("small projectile tracked: " + t.typeId);
            }
            h.succeed();
        });
    }

    /** Ожидаемый вид по классу аддона (по именам классов CBC-AT и Neo Warfare). */
    private static ProjectileType expectedByClass(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            switch (k.getName()) {
                case "com.dsvv.cbcat.cannon.medium_rocketpod.munitions.AbstractMediumRocket" -> { return ProjectileType.ROCKET; }
                case "com.dsvv.cbcat.cannon.rocketpod.munitions.AbstractRocket",
                     "com.dsvv.cbcat.cannon.heavy_autocannon.munitions.AbstractHeavyAutocannonProjectile",
                     "riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile",
                     "rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile" -> { return null; }
                case "rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile" -> {
                    return c.getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("rocket") ? ProjectileType.ROCKET : ProjectileType.SHELL;
                }
                default -> { }
            }
        }
        return null;
    }
}
