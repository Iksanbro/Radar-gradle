package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.countRounds;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;
import static dev.radarfcs.gametest.SettingsGameTests.configure;
import static dev.radarfcs.gametest.SettingsGameTests.tough;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Стрельба по разным целям: мобы с настоящим ИИ (ходят, летают, прыгают), большие и маленькие, цели выше и ниже
 * батареи, цель за стеной. Батарея - автопушка (4 ствола, пулемётные патроны) и вычислитель.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class TargetVarietyGameTests {
    private static final String ARENA = "arena";
    private static final BlockPos MOUNT = new BlockPos(10, 1, 32);
    private static final BlockPos FCS = new BlockPos(6, 1, 28);

    static void setup(ServerLevel l) {
        FcsGameTests.direct(l, 120);
    }

    @BeforeBatch(batch = "var_walk") public static void b1(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "var_walk") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "var_golem") public static void b2(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "var_golem") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "var_phantom") public static void b3(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "var_phantom") public static void a3(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "var_ghast") public static void b4(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "var_ghast") public static void a4(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "var_blaze") public static void b5(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "var_blaze") public static void a5(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "var_bee") public static void b6(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "var_bee") public static void a6(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "var_slime") public static void b7(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "var_slime") public static void a7(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "var_above") public static void b8(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "var_above") public static void a8(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "var_tower") public static void b9(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "var_tower") public static void a9(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "var_wall") public static void b10(ServerLevel l) { setup(l); }
    @AfterBatch(batch = "var_wall") public static void a10(ServerLevel l) { FcsGameTests.cleanup(l); }

    // ------------------------------------------------------------------ помощники

    /**
     * Батарея на высоте y (станок (10,y,32)), цель от spawn, ждать до minHits попаданий не дольше timeout тиков.
     * В лог - попадания и выпущенные патроны.
     */
    static void engage(GameTestHelper h, String name, int y, Function<FireControlBlockEntity, Mob> spawn, int minHits) {
        floor(h);
        BlockPos mount = MOUNT.atY(y), fcsPos = FCS.atY(y);
        if (y > 1) {
            // башня: опоры под станком, вычислителем и моторами
            for (int yy = 1; yy < y; yy++) {
                for (BlockPos p : new BlockPos[]{mount, fcsPos, mount.south(), mount.below(2)}) {
                    if (p.getY() > yy) h.setBlock(p.atY(yy), Blocks.STONE.defaultBlockState());
                }
            }
        }
        autocannon(h, mount, Direction.EAST, 4, 512);
        FireControlBlockEntity be = fcs(h, fcsPos);
        configure(be, s -> s.hostileOnly = false);                       // мирные (пчела, голем) - тоже цели
        Mob target = tough(spawn.apply(be));
        target.setPersistenceRequired();
        AtomicInteger hits = countHits(h, target), rounds = countRounds(h, mount);
        logEvery(h, be, name, 20);
        h.succeedWhen(() -> {
            if (hits.get() < minHits) throw new net.minecraft.gametest.framework.GameTestAssertException(
                    name + ": " + hits.get() + " hits of " + rounds.get() + " rounds so far");
            FcsMod.LOG.info("[radar_fcs test] {}: {} hits of {} rounds, target {} at {}", name, hits.get(), rounds.get(),
                    target.getType().toShortString(), target.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)));
        });
    }

    /** Ходить туда-обратно между двумя точками своим ходом (навигация моба). */
    static void patrol(GameTestHelper h, Mob mob, Vec3 aRel, Vec3 bRel, double speed) {
        Vec3 a = h.absoluteVec(aRel), b = h.absoluteVec(bRel);
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            int t = tick.getAndIncrement();
            if (t % 20 != 0 || !mob.isAlive()) return;
            Vec3 to = (t / 120) % 2 == 0 ? b : a;
            mob.getNavigation().moveTo(to.x, to.y, to.z, speed);
        });
    }

    // ------------------------------------------------------------------ тесты

    /** Зомби с ИИ ходит сам (навигация), не манекен. */
    @GameTest(template = ARENA, batch = "var_walk", timeoutTicks = 400)
    public static void walkingZombie(GameTestHelper h) {
        engage(h, "var_walk", 1, be -> {
            Mob z = h.spawn(EntityType.ZOMBIE, new Vec3(40.5, 1, 22.5));
            patrol(h, z, new Vec3(40.5, 1, 22.5), new Vec3(44.5, 1, 46.5), 1.0);
            return z;
        }, 5);
    }

    /** Железный голем - крупная цель, ходит сам. */
    @GameTest(template = ARENA, batch = "var_golem", timeoutTicks = 400)
    public static void walkingGolem(GameTestHelper h) {
        engage(h, "var_golem", 1, be -> {
            Mob g = h.spawn(EntityType.IRON_GOLEM, new Vec3(46.5, 1, 22.5));
            patrol(h, g, new Vec3(46.5, 1, 22.5), new Vec3(46.5, 1, 46.5), 0.9);
            return g;
        }, 5);
    }

    /** Фантом кружит в воздухе (свой ИИ), от солнца не горит. */
    @GameTest(template = ARENA, batch = "var_phantom", timeoutTicks = 500)
    public static void circlingPhantom(GameTestHelper h) {
        engage(h, "var_phantom", 1, be -> {
            Mob p = h.spawn(EntityType.PHANTOM, new Vec3(40.5, 18, 32.5));
            p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 100000, 0, false, false));
            return p;
        }, 3);
    }

    /** Гаст - большая медленная летающая цель высоко. */
    @GameTest(template = ARENA, batch = "var_ghast", timeoutTicks = 500)
    public static void floatingGhast(GameTestHelper h) {
        engage(h, "var_ghast", 1, be -> h.spawn(EntityType.GHAST, new Vec3(48.5, 20, 32.5)), 3);
    }

    /** Ифрит парит и смещается рывками. */
    @GameTest(template = ARENA, batch = "var_blaze", timeoutTicks = 500)
    public static void hoveringBlaze(GameTestHelper h) {
        engage(h, "var_blaze", 1, be -> h.spawn(EntityType.BLAZE, new Vec3(36.5, 5, 30.5)), 3);
    }

    /** Пчела - маленькая летающая цель с ИИ. */
    @GameTest(template = ARENA, batch = "var_bee", timeoutTicks = 500)
    public static void smallBee(GameTestHelper h) {
        engage(h, "var_bee", 1, be -> h.spawn(EntityType.BEE, new Vec3(34.5, 4, 32.5)), 2);
    }

    /** Большой слизень прыгает (вертикальное движение - шум прыжков). */
    @GameTest(template = ARENA, batch = "var_slime", timeoutTicks = 500)
    public static void jumpingSlime(GameTestHelper h) {
        engage(h, "var_slime", 1, be -> {
            net.minecraft.world.entity.monster.Slime s = h.spawn(EntityType.SLIME, new Vec3(40.5, 1, 32.5));
            s.setSize(4, true);
            return s;
        }, 3);
    }

    /** Цель высоко над батареей (24 блока): манекен ходит по воздуху. */
    @GameTest(template = ARENA, batch = "var_above", timeoutTicks = 400)
    public static void targetHighAbove(GameTestHelper h) {
        // потолок площадки теста - барьеры: цель выше него, убираем
        FcsGameTests.removeBarriers(h, new BlockPos(-2, 1, -2), new BlockPos(66, 40, 66));
        engage(h, "var_above", 1, be -> movingTarget(h, EntityType.HUSK, new Vec3(30.5, 25, 22.5), 0.12, 18), 3);
    }

    /** Батарея на башне (12 блоков), цель внизу ходит по земле. */
    @GameTest(template = ARENA, batch = "var_tower", timeoutTicks = 400)
    public static void batteryOnTower(GameTestHelper h) {
        engage(h, "var_tower", 13, be -> {
            Mob z = h.spawn(EntityType.ZOMBIE, new Vec3(30.5, 1, 22.5));
            patrol(h, z, new Vec3(30.5, 1, 22.5), new Vec3(34.5, 1, 44.5), 1.0);
            return z;
        }, 4);
    }

    /** Цель за каменной стеной: огня нет (линия огня закрыта); стену убрали - огонь и попадания. */
    @GameTest(template = ARENA, batch = "var_wall", timeoutTicks = 500)
    public static void noFireThroughWall(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 512);
        FireControlBlockEntity be = fcs(h, FCS);
        for (int z = 14; z <= 50; z++) for (int y = 1; y <= 12; y++) h.setBlock(new BlockPos(26, y, z), Blocks.STONE.defaultBlockState());
        Mob husk = tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 26.5), 0.1, 10));
        AtomicInteger hits = countHits(h, husk), rounds = countRounds(h, MOUNT);
        AtomicInteger blocked = new AtomicInteger();
        logEvery(h, be, "var_wall", 20);
        h.onEachTick(() -> {
            for (MountState m : be.mountStates()) if (m.status == Status.BLOCKED_TERRAIN) blocked.incrementAndGet();
        });
        h.runAtTickTime(160, () -> {
            if (rounds.get() > 0) h.fail(rounds.get() + " rounds fired into the wall");
            if (blocked.get() == 0) h.fail("no 'blocked by terrain' status behind the wall");
            for (int z = 14; z <= 50; z++) for (int y = 1; y <= 12; y++) h.setBlock(new BlockPos(26, y, z), Blocks.AIR.defaultBlockState());
        });
        h.succeedWhen(() -> {
            if (h.getTick() <= 160 || hits.get() < 3) throw new net.minecraft.gametest.framework.GameTestAssertException("no hits after the wall removed yet");
        });
    }
}
