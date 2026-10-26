package dev.radarfcs.gametest;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.registry.FcsRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.ICannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Игровые тесты на настоящем сервере с Create, CBC и Create Radars:
 * сборка пушки, наведение, выстрел и попадание по движущейся цели; отсев неподвижных целей;
 * транспондер «свой»; цели с радара Create Radars; автопушка.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class FcsGameTests {

    private static final String ARENA = "arena";

    // ----------------------------------------------------------------- настройка

    private static void commonConfig() {
        FcsConfig.MIN_RANGE.set(4.0);
        FcsConfig.LINK_RADIUS.set(16);
        FcsConfig.GLOW_TARGETS.set(true);
        FcsConfig.MARK_TARGETS.set(true);
        FcsConfig.HOSTILE_ONLY.set(true);
        FcsConfig.REQUIRE_LOS.set(true);
        FcsConfig.LOG_SHOTS.set(true);
        try {
            CBCConfigs.server().failure.disableAllFailure.set(true);   // без случайных разрывов стволов
        } catch (Throwable t) {
            FcsMod.LOG.warn("[radar_fcs test] could not disable CBC failures: {}", t.toString());
        }
    }

    /** Вычислители, созданные тестами: после партии их убираем, чтобы они не стреляли по соседним тестам. */
    private static final java.util.List<BlockPos> CREATED = new java.util.ArrayList<>();

    private static void cleanup(ServerLevel level) {
        for (BlockPos p : CREATED) {
            if (level.getBlockEntity(p) instanceof FireControlBlockEntity) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        CREATED.clear();
        java.util.List<net.minecraft.world.entity.Entity> doomed = new java.util.ArrayList<>();
        for (var e : level.getAllEntities()) {
            if (e instanceof net.minecraft.world.entity.monster.Husk || e instanceof Projectile) doomed.add(e);
        }
        doomed.forEach(net.minecraft.world.entity.Entity::discard);
    }

    private static void direct(ServerLevel level) {
        cleanup(level);
        commonConfig();
        FcsConfig.REQUIRE_RADAR.set(false);
        FcsConfig.SENSOR_MODE.set(FcsConfig.SensorMode.DIRECT);
        FcsConfig.SENSOR_RANGE.set(80.0);
    }

    @BeforeBatch(batch = "big") public static void b1(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "big") public static void a1(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "stationary") public static void b2(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "stationary") public static void a2(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "iff") public static void b3(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "iff") public static void a3(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "auto") public static void b4(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "auto") public static void a4(ServerLevel l) { cleanup(l); }

    @BeforeBatch(batch = "radar")
    public static void beforeRadar(ServerLevel level) {
        cleanup(level);
        commonConfig();
        FcsConfig.REQUIRE_RADAR.set(true);
        FcsConfig.SENSOR_MODE.set(FcsConfig.SensorMode.RADAR);
    }

    @AfterBatch(batch = "radar") public static void a5(ServerLevel l) { cleanup(l); }

    // ------------------------------------------------------------------ строители

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    private static void floor(GameTestHelper h) {
        BlockState stone = Blocks.SMOOTH_STONE.defaultBlockState();
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) h.setBlock(new BlockPos(x, 0, z), stone);
    }

    /**
     * Большая чугунная пушка на станке: казённик, chambers каморы с порохом, barrels стволов, ядро в первом стволе.
     * Пушка смотрит в сторону dir. Станок в mount, ось вращения на 2 блока выше.
     */
    private static void bigCannon(GameTestHelper h, BlockPos mount, Direction dir, int chambers, int barrels, boolean load) {
        h.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
        BlockPos pivot = mount.above(2);
        Direction facing = dir.getOpposite();
        h.setBlock(pivot.relative(dir, -chambers), block("createbigcannons:cast_iron_cannon_end").defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing));
        for (int i = 0; i < chambers; i++) {
            h.setBlock(pivot.relative(dir, -i), block("createbigcannons:cast_iron_cannon_chamber").defaultBlockState()
                    .setValue(BlockStateProperties.FACING, facing));
        }
        for (int i = 1; i <= barrels; i++) {
            h.setBlock(pivot.relative(dir, i), block("createbigcannons:cast_iron_cannon_barrel").defaultBlockState()
                    .setValue(BlockStateProperties.FACING, facing));
        }
        connect(h, pivot.relative(dir, -chambers), dir, chambers + barrels + 1);
        if (!load) return;
        BlockState powder = block("createbigcannons:powder_charge").defaultBlockState()
                .setValue(BlockStateProperties.AXIS, dir.getAxis());
        for (int i = 0; i < chambers; i++) loadInto(h, pivot.relative(dir, -i), powder);
        loadInto(h, pivot.relative(dir, 1), block("createbigcannons:solid_shot").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir));
    }

    /** Соединить соседние блоки пушки вдоль dir (как при установке игроком). */
    private static void connect(GameTestHelper h, BlockPos fromRel, Direction dir, int count) {
        for (int i = 0; i + 1 < count; i++) {
            BlockPos a = h.absolutePos(fromRel.relative(dir, i)), b = h.absolutePos(fromRel.relative(dir, i + 1));
            if (h.getLevel().getBlockEntity(a) instanceof ICannonBlockEntity<?> ca
                    && h.getLevel().getBlockEntity(b) instanceof ICannonBlockEntity<?> cb) {
                ca.cannonBehavior().setConnectedFace(dir, true);
                cb.cannonBehavior().setConnectedFace(dir.getOpposite(), true);
                h.getLevel().getBlockEntity(a).setChanged();
                h.getLevel().getBlockEntity(b).setChanged();
            } else {
                throw new GameTestAssertException("cannon blocks missing at " + fromRel.relative(dir, i));
            }
        }
    }

    private static void loadInto(GameTestHelper h, BlockPos rel, BlockState munition) {
        BlockPos abs = h.absolutePos(rel);
        if (!(h.getLevel().getBlockEntity(abs) instanceof IBigCannonBlockEntity cbe)) {
            throw new GameTestAssertException("no cannon block entity at " + rel);
        }
        cbe.cannonBehavior().loadBlock(new StructureBlockInfo(abs, munition, new CompoundTag()));
        h.getLevel().getBlockEntity(abs).setChanged();
    }

    /**
     * Чугунная автопушка как в ponder-сцене CBC: казённик позади оси, пружина отката на оси, затем стволы.
     * В казённик кладутся пулемётные патроны.
     */
    private static void autocannon(GameTestHelper h, BlockPos mount, Direction dir, int barrels, int rounds) {
        h.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
        BlockPos pivot = mount.above(2);
        h.setBlock(pivot.relative(dir, -1), block("createbigcannons:cast_iron_autocannon_breech").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir));
        h.setBlock(pivot, block("createbigcannons:cast_iron_autocannon_recoil_spring").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir));
        for (int i = 1; i <= barrels; i++) {
            h.setBlock(pivot.relative(dir, i), block("createbigcannons:cast_iron_autocannon_barrel").defaultBlockState()
                    .setValue(BlockStateProperties.FACING, dir));
        }
        connect(h, pivot.relative(dir, -1), dir, barrels + 2);
        BlockPos abs = h.absolutePos(pivot.relative(dir, -1));
        if (h.getLevel().getBlockEntity(abs) instanceof AbstractAutocannonBreechBlockEntity breech) {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse("createbigcannons:machine_gun_round"));
            for (int i = 0; i < rounds; i++) breech.getInputBuffer().add(new net.minecraft.world.item.ItemStack(item));
            breech.setChanged();
        } else {
            throw new GameTestAssertException("no autocannon breech at " + pivot.relative(dir, -1));
        }
    }

    private static FireControlBlockEntity fcs(GameTestHelper h, BlockPos rel) {
        h.setBlock(rel, FcsRegistry.FIRE_CONTROL.get().defaultBlockState());
        if (h.getLevel().getBlockEntity(h.absolutePos(rel)) instanceof FireControlBlockEntity be) {
            CREATED.add(h.absolutePos(rel));
            return be;
        }
        throw new GameTestAssertException("FCS block entity missing");
    }

    /** Цель без ИИ, которую тест двигает сам: туда-обратно вдоль оси Z с заданной скоростью. */
    private static Mob movingTarget(GameTestHelper h, EntityType<? extends Mob> type, Vec3 startRel, double speedPerTick,
                                    double span) {
        Mob mob = h.spawn(type, startRel);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        Vec3 start = h.absoluteVec(startRel);
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            if (!mob.isAlive() || speedPerTick == 0) return;
            int t = tick.getAndIncrement();
            double period = span / speedPerTick;
            double phase = (t % (2 * period));
            double off = phase < period ? phase * speedPerTick : (2 * period - phase) * speedPerTick;
            mob.setPos(start.x, start.y, start.z + off);
            mob.setDeltaMovement(Vec3.ZERO);
        });
        return mob;
    }

    private static void logEvery(GameTestHelper h, FireControlBlockEntity be, String name, int period) {
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            int t = tick.getAndIncrement();
            if (t % period != 0) return;
            StringBuilder sb = new StringBuilder();
            for (MountState m : be.mountStates()) {
                sb.append(String.format(" [%s %s yaw=%.1f pitch=%.1f err=%.2f tol=%.2f dist=%.1f learned=%d shots=%d muzzle=%.2f ammo=%s note=%s speedx=%.3f]",
                        m.mount == null ? "?" : m.mount.typeName(), m.status, m.cmdYaw, m.cmdPitch, m.aimError, m.tolerance,
                        m.distance, m.correction.shots(), m.shotsSeen, m.learnedMuzzle,
                        m.estimate == null ? "-" : m.estimate.ammo() + (m.estimate.loaded() ? "" : "(empty)") + " v=" + String.format("%.2f", m.estimate.model().muzzleSpeed()),
                        m.note, m.correction.speedRatio()));
            }
            FcsMod.LOG.info("[radar_fcs test] {} t={} targets={} tracks={}{}", name, t, be.sensor().active().size(),
                    be.sensor().all().size(), sb);
        });
    }

    /** Следит за снарядами CBC и пишет в лог минимальный промах каждого по цели (расстояние до её габарита). */
    private static void trackMiss(GameTestHelper h, Mob target, String name) {
        java.util.Map<Integer, double[]> best = new java.util.HashMap<>();
        java.util.Map<Integer, Vec3> lastPos = new java.util.HashMap<>();
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            int t = tick.getAndIncrement();
            AABB box = target.getBoundingBox();
            Vec3 c = box.getCenter();
            for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class,
                    new AABB(c, c).inflate(80))) {
                Vec3 prev = lastPos.getOrDefault(p.getId(), p.position());
                Vec3 cur = p.position();
                double d = Double.MAX_VALUE;
                for (int i = 0; i <= 10; i++) {
                    Vec3 q = prev.lerp(cur, i / 10.0);
                    double dx = Math.max(0, Math.max(box.minX - q.x, q.x - box.maxX));
                    double dy = Math.max(0, Math.max(box.minY - q.y, q.y - box.maxY));
                    double dz = Math.max(0, Math.max(box.minZ - q.z, q.z - box.maxZ));
                    d = Math.min(d, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
                lastPos.put(p.getId(), cur);
                double[] b = best.computeIfAbsent(p.getId(), k -> new double[]{Double.MAX_VALUE, t});
                if (d < b[0]) b[0] = d;
                if (t % 5 == 0 || p.isInGround()) FcsMod.LOG.info("[radar_fcs test] {} proj#{} t={} pos={} vel={} target={} miss={}",
                        name, p.getId(), t, fmt(cur), fmt(p.getDeltaMovement()), fmt(c), String.format("%.2f", b[0]));
            }
        });
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.2f %.2f %.2f)", v.x, v.y, v.z);
    }

    private static int projectilesNear(GameTestHelper h, double radius) {
        Vec3 c = h.absoluteVec(new Vec3(32, 4, 32));
        return h.getLevel().getEntitiesOfClass(Projectile.class, new AABB(c, c).inflate(radius)).size();
    }

    // ------------------------------------------------------------------- тесты

    /** Главный тест: пушка сама собирается, наводится с упреждением и попадает в движущуюся цель. */
    @GameTest(template = ARENA, batch = "big", timeoutTicks = 600)
    public static void bigCannonHitsMovingTarget(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(48.5, 1, 22.5), 0.12, 18);
        float max = husk.getMaxHealth();
        logEvery(h, be, "big", 20);
        trackMiss(h, husk, "big");
        h.succeedWhen(() -> {
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            MountState m = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            FcsMod.LOG.info("[radar_fcs test] big: HIT, health {} -> {}, shots seen {}", max, husk.getHealth(),
                    m == null ? -1 : m.shotsSeen);
        });
    }

    /** Неподвижная цель неактивна: огня нет, пушка в ожидании. */
    @GameTest(template = ARENA, batch = "stationary", timeoutTicks = 200)
    public static void stationaryTargetIgnored(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 32.5), 0, 0);
        float max = husk.getMaxHealth();
        logEvery(h, be, "stationary", 40);
        h.runAtTickTime(160, () -> {
            if (husk.getHealth() < max) h.fail(("stationary target was shot"));
            if (!be.sensor().active().isEmpty()) h.fail(("stationary target is active"));
            for (MountState m : be.mountStates()) {
                if (m.status == Status.FIRE) h.fail(("mount is firing at a stationary target"));
                if (m.status == Status.NOT_ASSEMBLED) h.fail(("mount was not assembled"));
            }
            h.succeed();
        });
    }

    /** Транспондер «свой» рядом с целью: цель своя, огня нет. Другой канал - чужая. */
    @GameTest(template = ARENA, batch = "iff", timeoutTicks = 260)
    public static void transponderProtectsTarget(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        h.setBlock(new BlockPos(48, 1, 30), FcsRegistry.IFF_TRANSPONDER.get().defaultBlockState());
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(46.5, 1, 26.5), 0.1, 6);
        float max = husk.getMaxHealth();
        logEvery(h, be, "iff", 40);
        h.runAtTickTime(220, () -> {
            if (husk.getHealth() < max) h.fail(("protected target was shot"));
            if (!be.sensor().active().isEmpty()) h.fail(("protected target is active"));
            h.succeed();
        });
    }

    /** Автопушка (пулемётные патроны) по движущейся цели: огонь очередями и попадание. */
    @GameTest(template = ARENA, batch = "auto", timeoutTicks = 600)
    public static void autocannonHitsMovingTarget(GameTestHelper h) {
        floor(h);
        autocannon(h, new BlockPos(10, 1, 32), Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16);
        float max = husk.getMaxHealth();
        logEvery(h, be, "auto", 20);
        h.succeedWhen(() -> {
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            FcsMod.LOG.info("[radar_fcs test] auto: HIT, health {} -> {}", max, husk.getHealth());
        });
    }

    /** Цели берутся с плоского радара Create Radars: без радара - статус «нет радара», с радаром - попадание. */
    @GameTest(template = ARENA, batch = "radar", timeoutTicks = 700)
    public static void radarDesignatedTarget(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(48.5, 1, 22.5), 0.12, 18);
        float max = husk.getMaxHealth();
        logEvery(h, be, "radar", 20);
        AtomicReference<Boolean> sawNoRadar = new AtomicReference<>(false);
        h.runAtTickTime(60, () -> {
            for (MountState m : be.mountStates()) if (m.status == Status.NO_RADAR) sawNoRadar.set(true);
            if (husk.getHealth() < max) h.fail(("fired without radar"));
            // плоский радар сканирует в сторону, противоположную FACING: FACING=запад -> луч на восток, к цели
            h.setBlock(new BlockPos(8, 1, 26), block("create_radar:plane_radar").defaultBlockState()
                    .trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        });
        h.succeedWhen(() -> {
            if (!sawNoRadar.get()) throw new GameTestAssertException("NO_RADAR status not observed before radar placed");
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            FcsMod.LOG.info("[radar_fcs test] radar: HIT via Create Radars tracks, radar used={}", be.sensor().usedRadar());
            if (!be.sensor().usedRadar()) throw new GameTestAssertException("radar tracks were not used");
        });
    }
}
