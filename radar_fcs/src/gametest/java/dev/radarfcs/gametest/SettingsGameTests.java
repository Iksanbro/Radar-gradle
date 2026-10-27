package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.fcs.MountState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Настройки вычислителя в игре: режимы огня (одиночный, очередь), типы целей, сброс статичных и после N выстрелов,
 * приоритет «наиболее дальняя», переключение после N выстрелов. У каждого теста своя партия: вычислитель видит
 * на 80 блоков и иначе стрелял бы по целям соседних тестов.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class SettingsGameTests {

    private static final String ARENA = "arena";
    private static final BlockPos MOUNT = new BlockPos(10, 1, 32);
    private static final BlockPos FCS = new BlockPos(6, 1, 28);

    @BeforeBatch(batch = "set_burst") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "set_burst") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "set_single") public static void b2(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "set_single") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "set_types") public static void b3(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "set_types") public static void a3(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "set_static") public static void b4(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "set_static") public static void a4(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "set_drop") public static void b5(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "set_drop") public static void a5(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "set_priority") public static void b6(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "set_priority") public static void a6(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "set_burst_rotary") public static void b8(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "set_burst_rotary") public static void a8(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "set_burst_medium") public static void b9(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "set_burst_medium") public static void a9(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "set_static_time") public static void b10(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "set_static_time") public static void a10(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "set_switch") public static void b7(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "set_switch") public static void a7(ServerLevel l) { FcsGameTests.cleanup(l); }

    // ----------------------------------------------------------------- помощники

    /** Изменить настройки вычислителя тем же путём, что и экран настроек. */
    static void configure(FireControlBlockEntity be, Consumer<EngageSettings> change) {
        EngageSettings s = be.settings().copy();
        change.accept(s);
        be.applySettings(s, null);
    }

    /** Цель, которую автопушка не убьёт за время теста. */
    static Mob tough(Mob m) {
        var hp = m.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        m.setHealth(m.getMaxHealth());
        return m;
    }

    /** Тики, в которые из пушки вылетели снаряды (снаряд впервые замечен у станка). */
    static List<Long> roundTicks(GameTestHelper h, BlockPos mountRel) {
        List<Long> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        Vec3 c = h.absoluteVec(Vec3.atCenterOf(mountRel));
        h.onEachTick(() -> {
            for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class, new AABB(c, c).inflate(160))) {
                if (seen.add(p.getId()) && p.position().distanceTo(c) < FcsGameTests.MUZZLE_ZONE) out.add(h.getTick());
            }
        });
        return out;
    }

    /** Выстрелы, разбитые на очереди: новая очередь - после перерыва больше gap тиков. */
    static List<List<Long>> salvos(List<Long> ticks, long gap) {
        List<List<Long>> out = new ArrayList<>();
        for (long t : ticks) {
            if (out.isEmpty() || t - out.get(out.size() - 1).get(out.get(out.size() - 1).size() - 1) > gap) out.add(new ArrayList<>());
            out.get(out.size() - 1).add(t);
        }
        return out;
    }

    /** Все очереди, кроме последней (она может быть не закончена), - ровно по size выстрелов, перерывы не короче pause. */
    static void checkSalvos(GameTestHelper h, String name, List<Long> ticks, long gap, int size, long pause, int minSalvos) {
        List<List<Long>> g = salvos(ticks, gap);
        FcsMod.LOG.info("[radar_fcs test] {}: rounds at {} -> salvos {}", name, ticks, g);
        if (g.size() < minSalvos) h.fail(name + ": only " + g.size() + " salvos: " + g);
        for (int i = 0; i + 1 < g.size(); i++) {
            List<Long> s = g.get(i);
            if (s.size() != size) h.fail(name + ": salvo " + i + " has " + s.size() + " rounds, expected " + size + ": " + g);
            long br = g.get(i + 1).get(0) - s.get(s.size() - 1);
            if (br < pause) h.fail(name + ": pause after salvo " + i + " is " + br + " ticks, expected >= " + pause + ": " + g);
        }
    }

    // ------------------------------------------------------------------- тесты

    /** Очередь: по 4 выстрела, пауза 40 тиков. */
    @GameTest(template = ARENA, batch = "set_burst", timeoutTicks = 520)
    public static void burstModeFiresBurstsWithPauses(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 128);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> { s.fireMode = EngageSettings.FireMode.BURST; s.burstLength = 4; s.pauseTicks = 40; });
        tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16));
        List<Long> rounds = roundTicks(h, MOUNT);
        logEvery(h, be, "set_burst", 40);
        h.runAtTickTime(480, () -> {
            checkSalvos(h, "set_burst", rounds, 25, 4, 40, 4);
            h.succeed();
        });
    }

    /** Очередь роторной пушки CBC Neo Warfare (стреляет каждый тик, пока есть сигнал): по 6 выстрелов, пауза 30 тиков. */
    @GameTest(template = ARENA, batch = "set_burst_rotary", timeoutTicks = 440)
    public static void burstModeRotaryCannon(GameTestHelper h) {
        floor(h);
        if (!FcsGameTests.rotaryCannon(h, MOUNT, Direction.EAST, "bronze", 3, 512)) {
            h.succeed();                                                       // без Neo Warfare тест не применим
            return;
        }
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> { s.fireMode = EngageSettings.FireMode.BURST; s.burstLength = 6; s.pauseTicks = 30; });
        tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16));
        List<Long> rounds = roundTicks(h, MOUNT);
        logEvery(h, be, "set_burst_rotary", 40);
        h.runAtTickTime(400, () -> {
            checkSalvos(h, "set_burst_rotary", rounds, 20, 6, 30, 5);
            h.succeed();
        });
    }

    /** Очередь средней пушки CBC Neo Warfare (выстрел по фронту сигнала, автомат заряжания 5 тиков): по 3, пауза 40. */
    @GameTest(template = ARENA, batch = "set_burst_medium", timeoutTicks = 440)
    public static void burstModeMediumCannon(GameTestHelper h) {
        floor(h);
        if (!FcsGameTests.mediumCannon(h, MOUNT, Direction.EAST, "nethersteel", 3)) {
            h.succeed();
            return;
        }
        FcsGameTests.mediumAutoloader(h, MOUNT, 5);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> { s.fireMode = EngageSettings.FireMode.BURST; s.burstLength = 3; s.pauseTicks = 40; });
        tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16));
        List<Long> rounds = roundTicks(h, MOUNT);
        logEvery(h, be, "set_burst_medium", 40);
        h.runAtTickTime(400, () -> {
            checkSalvos(h, "set_burst_medium", rounds, 25, 3, 40, 4);
            h.succeed();
        });
    }

    /** Одиночный: по одному выстрелу, пауза 30 тиков. */
    @GameTest(template = ARENA, batch = "set_single", timeoutTicks = 400)
    public static void singleModeFiresOneRoundPerPause(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> { s.fireMode = EngageSettings.FireMode.SINGLE; s.pauseTicks = 30; });
        tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16));
        List<Long> rounds = roundTicks(h, MOUNT);
        logEvery(h, be, "set_single", 40);
        h.runAtTickTime(360, () -> {
            checkSalvos(h, "set_single", rounds, 15, 1, 30, 5);
            h.succeed();
        });
    }

    /** Мобы выключены в типах целей - огня нет; включили - огонь. */
    @GameTest(template = ARENA, batch = "set_types", timeoutTicks = 500)
    public static void disabledTargetTypeIsIgnored(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> s.enabled[EngageSettings.Category.MOBS.ordinal()] = false);
        tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16));
        List<Long> rounds = roundTicks(h, MOUNT);
        logEvery(h, be, "set_types", 40);
        h.runAtTickTime(150, () -> {
            if (!rounds.isEmpty()) h.fail("fired at a disabled target type: " + rounds);
            if (!be.sensor().active().isEmpty()) h.fail("disabled target type is active");
            if (be.sensor().all().isEmpty()) h.fail("target was not seen at all");
            configure(be, s -> s.enabled[EngageSettings.Category.MOBS.ordinal()] = true);
        });
        h.succeedWhen(() -> {
            if (h.getTick() <= 150 || rounds.isEmpty()) throw new GameTestAssertException("no fire after enabling mobs yet");
        });
    }

    /** «Сбрасывать статичные» выключено - огонь по стоящей цели (по умолчанию её не трогают, см. stationaryTargetIgnored). */
    @GameTest(template = ARENA, batch = "set_static", timeoutTicks = 400)
    public static void staticTargetEngagedWhenAllowed(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> s.dropStatic = false);
        Mob husk = tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 32.5), 0, 0));
        AtomicInteger hits = countHits(h, husk);
        logEvery(h, be, "set_static", 40);
        h.succeedWhen(() -> {
            if (hits.get() == 0) throw new GameTestAssertException("static target not hit yet");
        });
    }

    /** Сброс после 5 выстрелов: ровно 5 выстрелов по цели, потом цель не берётся. */
    @GameTest(template = ARENA, batch = "set_drop", timeoutTicks = 400)
    public static void targetDroppedAfterShots(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> s.dropAfterShots = 5);
        Mob husk = tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16));
        List<Long> rounds = roundTicks(h, MOUNT);
        logEvery(h, be, "set_drop", 40);
        h.runAtTickTime(360, () -> {
            FcsMod.LOG.info("[radar_fcs test] set_drop: rounds at {}", rounds);
            if (rounds.size() != 5) h.fail("expected exactly 5 rounds, got " + rounds.size() + ": " + rounds);
            if (!be.isDropped(husk.getUUID().toString())) h.fail("target is not dropped");
            if (!be.sensor().active().isEmpty()) h.fail("dropped target is still active");
            h.succeed();
        });
    }

    /** Приоритет «наиболее дальняя»: пушка держит дальнюю цель, ближнюю не трогает. */
    @GameTest(template = ARENA, batch = "set_priority", timeoutTicks = 400)
    public static void farthestPriorityPicksFarTarget(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> s.priority = EngageSettings.Priority.FARTHEST);
        Mob near = tough(movingTarget(h, EntityType.HUSK, new Vec3(26.5, 1, 42.5), 0.15, 6));
        Mob far = tough(movingTarget(h, EntityType.HUSK, new Vec3(46.5, 1, 20.5), 0.15, 10));
        AtomicInteger nearHits = countHits(h, near), farHits = countHits(h, far);
        // ближнюю можно брать, только пока дальняя не годится (манекен на развороте на пару тиков стоит - статичная)
        String nearId = near.getUUID().toString(), farId = far.getUUID().toString();
        AtomicInteger violations = new AtomicInteger(), nearTicks = new AtomicInteger();
        long[] farActiveSince = {Long.MAX_VALUE};
        h.onEachTick(() -> {
            boolean farActive = false;
            for (var t : be.sensor().active()) if (t.id.equals(farId)) farActive = true;
            if (!farActive) farActiveSince[0] = Long.MAX_VALUE;
            else if (farActiveSince[0] == Long.MAX_VALUE) farActiveSince[0] = h.getTick();
            for (MountState m : be.mountStates()) {
                if (!nearId.equals(m.targetId)) continue;
                nearTicks.incrementAndGet();
                if (h.getTick() - farActiveSince[0] > 3) violations.incrementAndGet();
            }
        });
        logEvery(h, be, "set_priority", 40);
        h.runAtTickTime(300, () -> {
            FcsMod.LOG.info("[radar_fcs test] set_priority: far hits {}, near hits {}, near targeted {} ticks ({} while far was available)",
                    farHits.get(), nearHits.get(), nearTicks.get(), violations.get());
            if (farHits.get() == 0) h.fail("far target not hit");
            if (violations.get() > 0) h.fail("mount aimed at the near target while the far one was available");
            h.succeed();
        });
    }

    /**
     * Время стояния до сброса (1 с по умолчанию): цель остановилась на 8 тиков - остаётся целью; стоит дольше - сброшена.
     * Движение: 60 тиков вперёд, 8 стоит, 60 вперёд, дальше стоит.
     */
    @GameTest(template = ARENA, batch = "set_static_time", timeoutTicks = 300)
    public static void briefStopIsNotStatic(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        Mob husk = tough(h.spawn(EntityType.HUSK, new Vec3(40.5, 1, 22.5)));
        husk.setNoAi(true);
        Vec3 start = h.absoluteVec(new Vec3(40.5, 1, 22.5));
        String id = husk.getUUID().toString();
        AtomicInteger lostOnBriefStop = new AtomicInteger(), keptWhenParked = new AtomicInteger(), activeTicks = new AtomicInteger();
        h.onEachTick(() -> {
            long t = h.getTick();
            double moved = t < 60 ? t : t < 68 ? 60 : t < 128 ? t - 8 : 120;
            husk.setPos(start.x, start.y, start.z + 0.15 * moved);
            husk.setDeltaMovement(Vec3.ZERO);
            boolean active = false;
            for (var tr : be.sensor().active()) if (tr.id.equals(id)) active = true;
            if (active) activeTicks.incrementAndGet();
            if (t >= 45 && t < 128 && !active) lostOnBriefStop.incrementAndGet();
            if (t >= 165 && active) keptWhenParked.incrementAndGet();
        });
        logEvery(h, be, "set_static_time", 20);
        h.runAtTickTime(280, () -> {
            FcsMod.LOG.info("[radar_fcs test] set_static_time: active {} ticks, lost during brief stop {}, active when parked {}",
                    activeTicks.get(), lostOnBriefStop.get(), keptWhenParked.get());
            if (lostOnBriefStop.get() > 0) h.fail("target dropped during a brief stop for " + lostOnBriefStop.get() + " ticks");
            if (keptWhenParked.get() > 0) h.fail("parked target still active for " + keptWhenParked.get() + " ticks");
            h.succeed();
        });
    }

    /** Переключение после 3 выстрелов: огонь ходит между двумя целями, обе получают попадания. */
    @GameTest(template = ARENA, batch = "set_switch", timeoutTicks = 600)
    public static void switchAfterShotsAlternatesTargets(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 128);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> { s.switchMode = EngageSettings.SwitchMode.AFTER_SHOTS; s.switchShots = 3; });
        Mob a = tough(movingTarget(h, EntityType.HUSK, new Vec3(36.5, 1, 22.5), 0.15, 8));
        Mob b = tough(movingTarget(h, EntityType.HUSK, new Vec3(44.5, 1, 38.5), 0.15, 8));
        AtomicInteger hitsA = countHits(h, a), hitsB = countHits(h, b);
        AtomicInteger switches = new AtomicInteger();
        String[] last = {null};
        h.onEachTick(() -> {
            for (MountState m : be.mountStates()) {
                if (m.targetId != null && !m.targetId.equals(last[0])) {
                    if (last[0] != null) switches.incrementAndGet();
                    last[0] = m.targetId;
                }
            }
        });
        logEvery(h, be, "set_switch", 40);
        h.succeedWhen(() -> {
            if (switches.get() < 4 || hitsA.get() == 0 || hitsB.get() == 0) {
                throw new GameTestAssertException("switches " + switches.get() + ", hits " + hitsA.get() + "/" + hitsB.get());
            }
            FcsMod.LOG.info("[radar_fcs test] set_switch: switches {}, hits {}/{}", switches.get(), hitsA.get(), hitsB.get());
        });
    }
}
