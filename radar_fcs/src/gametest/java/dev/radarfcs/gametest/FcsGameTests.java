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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
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

    static void commonConfig() {
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
    static final java.util.List<BlockPos> CREATED = new java.util.ArrayList<>();

    /** Чанки, загруженные тестами принудительно (трассы быстрых целей выходят за площадку теста); остаются загруженными. */
    static final java.util.List<long[]> FORCED = new java.util.ArrayList<>();

    static void forceArea(GameTestHelper h, BlockPos fromRel, BlockPos toRel) {
        BlockPos a = h.absolutePos(fromRel), b = h.absolutePos(toRel);
        ServerLevel l = h.getLevel();
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                if (l.setChunkForced(cx, cz, true)) FORCED.add(new long[]{cx, cz});
            }
        }
    }

    static void cleanup(ServerLevel level) {
        // принудительную загрузку не снимаем: те же чанки могут уже принадлежать площадке следующего теста
        FORCED.clear();
        // блоки мода (вычислители, радары, мониторы): иначе они работают и в соседних тестах
        for (BlockPos p : CREATED) {
            if (FcsMod.MODID.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getNamespace())) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
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

    @BeforeBatch(batch = "rocket") public static void b6(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "rocket") public static void a6(ServerLevel l) { cleanup(l); }

    @BeforeBatch(batch = "ship") public static void b7(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "ship") public static void a7(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "shipiff") public static void b8(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "shipiff") public static void a8(ServerLevel l) { cleanup(l); }

    @BeforeBatch(batch = "fastauto") public static void b9(ServerLevel l) { direct(l); FcsConfig.SENSOR_RANGE.set(160.0); }
    @AfterBatch(batch = "fastauto") public static void a9(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "fastauto100") public static void b12(ServerLevel l) { direct(l); FcsConfig.SENSOR_RANGE.set(160.0); }
    @AfterBatch(batch = "fastauto100") public static void a12(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "fastdive") public static void b13(ServerLevel l) { direct(l); FcsConfig.SENSOR_RANGE.set(160.0); }
    @AfterBatch(batch = "fastdive") public static void a13(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "fastship") public static void b10(ServerLevel l) { direct(l); FcsConfig.SENSOR_RANGE.set(160.0); }
    @AfterBatch(batch = "fastship") public static void a10(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "nopower") public static void b11(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "nopower") public static void a11(ServerLevel l) { cleanup(l); }

    @BeforeBatch(batch = "radar")
    public static void beforeRadar(ServerLevel level) {
        cleanup(level);
        commonConfig();
        FcsConfig.REQUIRE_RADAR.set(true);
        FcsConfig.SENSOR_MODE.set(FcsConfig.SensorMode.RADAR);
    }

    @AfterBatch(batch = "radar") public static void a5(ServerLevel l) { cleanup(l); }

    // ------------------------------------------------------------------ строители

    static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    static void floor(GameTestHelper h) {
        BlockState stone = Blocks.SMOOTH_STONE.defaultBlockState();
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) h.setBlock(new BlockPos(x, 0, z), stone);
    }

    /**
     * Большая чугунная пушка на станке: казённик, chambers каморы с порохом, barrels стволов, ядро в первом стволе.
     * Пушка смотрит в сторону dir. Станок в mount, ось вращения на 2 блока выше.
     */
    static void bigCannon(GameTestHelper h, BlockPos mount, Direction dir, int chambers, int barrels, boolean load) {
        bigCannon(h, mount, dir, chambers, barrels, load, true);
    }

    /**
     * Вращение на валы станка: снизу - поворот, сбоку (по оси наклона) - наклон. Творческие моторы, rpm об/мин.
     * Без вращения вычислитель не наводит (requireRotation).
     */
    static void powerMount(GameTestHelper h, BlockPos mount, Direction dir, int rpm) {
        motor(h, mount.below(), Direction.UP, rpm);
        Direction side = dir.getClockWise();
        motor(h, mount.relative(side), side.getOpposite(), rpm);
    }

    static void motor(GameTestHelper h, BlockPos rel, Direction facing, int rpm) {
        h.setBlock(rel, block("create:creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, facing));
        if (h.getLevel().getBlockEntity(h.absolutePos(rel))
                instanceof com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity m) {
            m.generatedSpeed.setValue(rpm);
        } else {
            throw new GameTestAssertException("creative motor missing at " + rel);
        }
    }

    static void bigCannon(GameTestHelper h, BlockPos mount, Direction dir, int chambers, int barrels, boolean load, boolean power) {
        h.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
        if (power) powerMount(h, mount, dir, 256);
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
        powerMount(h, mount, dir, 256);
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

    /** Ракетная установка CBC Advanced Technologies: казённик позади оси, направляющие дальше. */
    private static boolean rocketPod(GameTestHelper h, BlockPos mount, Direction dir, int rails, String rocketItem, int rockets) {
        Block breechBlock = block("cbc_at:cast_iron_rocket_pod_breech");
        if (breechBlock == Blocks.AIR) return false;                       // CBC-AT не установлен
        try {
            // CBC-AT 0.1.4c вызывает onRecoil(Vec3, AbstractContraptionEntity), которого нет в CBC 5.11.4+
            rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption.class.getMethod("onRecoil",
                    Vec3.class, com.simibubi.create.content.contraptions.AbstractContraptionEntity.class);
        } catch (NoSuchMethodException e) {
            FcsMod.LOG.warn("[radar_fcs test] rocket: CBC-AT 0.1.4c is binary-incompatible with this CBC version, test skipped");
            return false;
        }
        h.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
        powerMount(h, mount, dir, 256);
        BlockPos pivot = mount.above(2);
        h.setBlock(pivot.relative(dir, -1), breechBlock.defaultBlockState().trySetValue(BlockStateProperties.FACING, dir));
        for (int i = 0; i < rails; i++) {
            h.setBlock(pivot.relative(dir, i), block("cbc_at:cast_iron_rocket_pod_rail").defaultBlockState()
                    .trySetValue(BlockStateProperties.FACING, dir));
        }
        connect(h, pivot.relative(dir, -1), dir, rails + 1);
        var be = h.getLevel().getBlockEntity(h.absolutePos(pivot.relative(dir, -1)));
        try {
            @SuppressWarnings("unchecked")
            java.util.Deque<net.minecraft.world.item.ItemStack> q = (java.util.Deque<net.minecraft.world.item.ItemStack>)
                    be.getClass().getMethod("getInputBuffer").invoke(be);
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(rocketItem));
            for (int i = 0; i < rockets; i++) q.add(new net.minecraft.world.item.ItemStack(item));
            be.setChanged();
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("cannot load rocket pod: " + e);
        }
        return true;
    }

    static FireControlBlockEntity fcs(GameTestHelper h, BlockPos rel) {
        h.setBlock(rel, FcsRegistry.FIRE_CONTROL.get().defaultBlockState());
        if (h.getLevel().getBlockEntity(h.absolutePos(rel)) instanceof FireControlBlockEntity be) {
            CREATED.add(h.absolutePos(rel));
            return be;
        }
        throw new GameTestAssertException("FCS block entity missing");
    }

    /** Цель без ИИ, которую тест двигает сам: туда-обратно вдоль оси Z с заданной скоростью. */
    static Mob movingTarget(GameTestHelper h, EntityType<? extends Mob> type, Vec3 startRel, double speedPerTick,
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

    static void logEvery(GameTestHelper h, FireControlBlockEntity be, String name, int period) {
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            int t = tick.getAndIncrement();
            if (t % period != 0) return;
            StringBuilder sb = new StringBuilder();
            for (MountState m : be.mountStates()) {
                sb.append(String.format(" [%s %s yaw=%.1f pitch=%.1f err=%.2f miss=%.2f allowed=%.2f dist=%.1f learned=%d shots=%d muzzle=%.2f ammo=%s note=%s speedx=%.3f]",
                        m.mount == null ? "?" : m.mount.typeName(), m.status, m.cmdYaw, m.cmdPitch, m.aimError, m.predictedMiss, m.tolerance,
                        m.distance, m.correction.shots(), m.shotsSeen, m.learnedMuzzle,
                        m.estimate == null ? "-" : m.estimate.ammo() + (m.estimate.loaded() ? "" : "(empty)") + " v=" + String.format("%.2f", m.estimate.model().muzzleSpeed()),
                        m.note, m.correction.speedRatio()));
            }
            FcsMod.LOG.info("[radar_fcs test] {} t={} targets={} tracks={}{}", name, t, be.sensor().active().size(),
                    be.sensor().all().size(), sb);
        });
    }

    /** Следит за снарядами CBC и пишет в лог минимальный промах каждого по цели (расстояние до её габарита). */
    static void trackMiss(GameTestHelper h, Mob target, String name) {
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

    /**
     * Ракеты CBC-AT летят с тягой: до первого пуска модель их не знает. Вычислитель следит за ракетами,
     * учит поправки полёта и после этого попадает.
     */
    @GameTest(template = ARENA, batch = "rocket", timeoutTicks = 900)
    public static void rocketPodLearnsAndHits(GameTestHelper h) {
        floor(h);
        if (!rocketPod(h, new BlockPos(10, 1, 32), Direction.EAST, 3, "cbc_at:ap_rocket_item", 96)) {
            h.succeed();                                                       // без CBC-AT тест не применим
            return;
        }
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(46.5, 1, 24.5), 0.1, 14);
        float max = husk.getMaxHealth();
        logEvery(h, be, "rocket", 20);
        h.succeedWhen(() -> {
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            MountState m = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            FcsMod.LOG.info("[radar_fcs test] rocket: HIT, health {} -> {}, learned shots {}, learned ticks {}, speed x{}",
                    max, husk.getHealth(), m == null ? -1 : m.correction.shots(), m == null ? -1 : m.correction.learnedTicks(),
                    m == null ? -1 : m.correction.speedRatio());
        });
    }

    /** Летящая цель без ИИ и гравитации: туда-обратно по прямой со скоростью velPerTick, legTicks тиков в одну сторону. */
    static Mob linearTarget(GameTestHelper h, EntityType<? extends Mob> type, Vec3 startRel, Vec3 velPerTick, int legTicks) {
        Mob mob = h.spawn(type, startRel);
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setPersistenceRequired();
        Vec3 start = h.absoluteVec(startRel);
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            if (!mob.isAlive()) return;
            int t = tick.getAndIncrement();
            int ph = t % (2 * legTicks);
            double k = ph < legTicks ? ph : 2 * legTicks - ph;
            Vec3 p = start.add(velPerTick.scale(k));
            mob.setPos(p.x, p.y, p.z);
            mob.setDeltaMovement(Vec3.ZERO);
        });
        return mob;
    }

    /** Снаряды, вылетевшие из пушки (новые снаряды CBC рядом со станком). */
    static AtomicInteger countRounds(GameTestHelper h, BlockPos mountRel) {
        AtomicInteger n = new AtomicInteger();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        Vec3 c = h.absoluteVec(Vec3.atCenterOf(mountRel));
        h.onEachTick(() -> {
            for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class, new AABB(c, c).inflate(160))) {
                if (seen.add(p.getId())) n.incrementAndGet();
            }
        });
        return n;
    }

    /** Попадания по цели: события урона от снарядов CBC. */
    static AtomicInteger countHits(GameTestHelper h, Mob target) {
        AtomicInteger hits = new AtomicInteger();
        HITS.put(target, src -> hits.incrementAndGet());
        return hits;
    }

    /** Слушатели урона по тестовым целям. */
    static final java.util.Map<net.minecraft.world.entity.Entity, java.util.function.Consumer<net.minecraft.world.entity.Entity>> HITS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static void onDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) {
        var l = HITS.get(e.getEntity());
        if (l == null) return;
        // урон снарядов CBC приходит без прямой сущности; удушье в блоках и пр. не считаем
        if (e.getSource().getMsgId().startsWith("createbigcannons")) l.accept(null);
    }

    /**
     * Автопушка по живучей цели (здоровье 2000), летящей по прямой туда-обратно: 300 тиков огня,
     * в лог - доля попаданий. Тест проходит, если попала хотя бы четверть снарядов (и не меньше 3).
     */
    private static void fastAutocannon(GameTestHelper h, String name, Vec3 startRel, Vec3 vel, int leg) {
        floor(h);
        autocannon(h, new BlockPos(10, 1, 32), Direction.EAST, 4, 512);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Vec3 endRel = startRel.add(vel.scale(leg));
        forceArea(h, BlockPos.containing(Math.min(startRel.x, endRel.x) - 16, 0, Math.min(startRel.z, endRel.z) - 16),
                BlockPos.containing(Math.max(startRel.x, endRel.x) + 16, 0, Math.max(startRel.z, endRel.z) + 16));
        Mob target = linearTarget(h, EntityType.HUSK, startRel, vel, leg);
        var hp = target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1024);
        target.setHealth(target.getMaxHealth());
        logEvery(h, be, name, 10);
        AtomicInteger hits = countHits(h, target), rounds = countRounds(h, new BlockPos(10, 1, 32));
        h.runAtTickTime(300, () -> {
            double rate = rounds.get() == 0 ? 0 : 100.0 * hits.get() / rounds.get();
            FcsMod.LOG.info("[radar_fcs test] {}: {} hits of {} rounds ({}%) on a {} m/s target", name, hits.get(), rounds.get(),
                    String.format(java.util.Locale.ROOT, "%.0f", rate), Math.round(vel.length() * 20));
            if (hits.get() < 3 || rate < 25) h.fail(name + ": " + hits.get() + " hits of " + rounds.get() + " rounds");
            h.succeed();
        });
    }

    /** Автопушка по летящей цели со скоростью 3.5 бл/тик (70 м/с), курс поперёк линии огня. */
    @GameTest(template = ARENA, batch = "fastauto", timeoutTicks = 400)
    public static void autocannonHitsFastTarget(GameTestHelper h) {
        fastAutocannon(h, "fastauto", new Vec3(46.5, 9, -37.5), new Vec3(0, 0, 3.5), 40);
    }

    /** То же на 5 бл/тик (100 м/с). */
    @GameTest(template = ARENA, batch = "fastauto100", timeoutTicks = 400)
    public static void autocannonHits100msTarget(GameTestHelper h) {
        fastAutocannon(h, "fastauto100", new Vec3(50.5, 12, -42.5), new Vec3(0, 0, 5.0), 30);
    }

    /** Пикирующая цель 70 м/с: сближается, снижается и смещается вбок одновременно. */
    @GameTest(template = ARENA, batch = "fastdive", timeoutTicks = 400)
    public static void autocannonHitsDivingTarget(GameTestHelper h) {
        fastAutocannon(h, "fastdive", new Vec3(110.5, 40, -20.5), new Vec3(-2.0, -1.0, 2.7), 30);
    }

    /**
     * Три большие пушки по одному выстрелу (8 бл/тик) по кораблю Sable 3x3x3, летящему со скоростью 3.5 бл/тик (70 м/с).
     * У CBC есть собственный случайный разброс снарядов, поэтому требуется хотя бы одно попадание из трёх.
     */
    @GameTest(template = ARENA, batch = "fastship", timeoutTicks = 400)
    public static void bigCannonHitsFastShip(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        for (int z : new int[]{20, 32, 44}) bigCannon(h, new BlockPos(10, 1, z), Direction.EAST, 4, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 30));
        forceArea(h, new BlockPos(30, 0, -80), new BlockPos(60, 0, 130));
        Object sub = SableTestSupport.ship(h, new BlockPos(45, 9, -60), 3, new Vec3(0, 0, 3.5), 175, false);
        logEvery(h, be, "fastship", 5);
        AtomicInteger hits = SableTestSupport.watchShipHits(h, sub, "fastship");
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(tick::incrementAndGet);
        h.succeedWhen(() -> {
            int fired = 0;
            for (MountState m : be.mountStates()) if (m.shotsSeen > 0) fired++;
            if (hits.get() == 0 || (fired < 3 && tick.get() < 250)) throw new GameTestAssertException("fast ship: hits " + hits.get());
            FcsMod.LOG.info("[radar_fcs test] fastship: {} hits of {} shots on a 70 m/s ship", hits.get(), fired);
        });
    }

    /** Без вращения на валах станка наведения нет (статус «нет вращения»); с вращением - попадание. */
    @GameTest(template = ARENA, batch = "nopower", timeoutTicks = 600)
    public static void noRotationNoAiming(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32);
        bigCannon(h, mount, Direction.EAST, 2, 4, true, false);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(48.5, 1, 22.5), 0.12, 18);
        float max = husk.getMaxHealth();
        logEvery(h, be, "nopower", 20);
        AtomicReference<Boolean> sawNoPower = new AtomicReference<>(false);
        h.runAtTickTime(80, () -> {
            for (MountState m : be.mountStates()) if (m.status == Status.NO_POWER) sawNoPower.set(true);
            if (husk.getHealth() < max) h.fail("fired without rotation");
            powerMount(h, mount, Direction.EAST, 256);
        });
        h.succeedWhen(() -> {
            if (!sawNoPower.get()) throw new GameTestAssertException("NO_POWER status not observed without rotation");
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
        });
    }

    /** Цели берутся с плоского радара Create Radars: без радара - статус «нет радара», с радаром - попадание. */
    @GameTest(template = ARENA, batch = "radar", timeoutTicks = 700)
    public static void radarDesignatedTarget(GameTestHelper h) {
        if (!ModList.get().isLoaded("create_radar")) {
            h.succeed();                                                       // Create Radars не установлен
            return;
        }
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
    /** Корабль Create Aeronautics / Sable - цель: вычислитель стреляет и попадает по движущемуся кораблю. */
    @GameTest(template = ARENA, batch = "ship", timeoutTicks = 600)
    public static void shipIsEngaged(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();                                                       // без Sable тест не применим
            return;
        }
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Object sub = SableTestSupport.ship(h, new BlockPos(46, 9, 22), 3, new Vec3(0, 0, 0.12), 14, false);
        logEvery(h, be, "ship", 20);
        AtomicInteger hits = SableTestSupport.watchShipHits(h, sub, "ship");
        h.succeedWhen(() -> {
            if (hits.get() == 0) throw new GameTestAssertException("ship not hit yet");
            FcsMod.LOG.info("[radar_fcs test] ship: HIT, tracks={}", be.sensor().all().size());
        });
    }

    /** Транспондер на корабле: весь корабль свой, огня нет. */
    @GameTest(template = ARENA, batch = "shipiff", timeoutTicks = 260)
    public static void shipWithTransponderIsFriendly(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Object sub = SableTestSupport.ship(h, new BlockPos(46, 9, 22), 3, new Vec3(0, 0, 0.12), 14, true);
        logEvery(h, be, "shipiff", 40);
        AtomicInteger hits = SableTestSupport.watchShipHits(h, sub, "shipiff");
        h.runAtTickTime(220, () -> {
            if (hits.get() > 0) h.fail("friendly ship was shot");
            boolean seen = false;
            for (var t : be.sensor().all()) if (t.kind == dev.radarfcs.core.Track.Kind.SHIP) seen = true;
            if (!seen) h.fail("ship was not detected at all");
            if (!be.sensor().active().isEmpty()) h.fail("friendly ship is active");
            h.succeed();
        });
    }
}
