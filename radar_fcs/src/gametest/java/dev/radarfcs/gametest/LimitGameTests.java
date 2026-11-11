package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AimControllerBlockEntity;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.GunDriveBlock;
import dev.radarfcs.block.GunDriveBlockEntity;
import dev.radarfcs.core.Aim;
import dev.radarfcs.core.AngleLimit;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Пределы поворота (ползунки контроллеров наводки и приводов орудия). */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class LimitGameTests {
    private static final String ARENA = "arena";

    @BeforeBatch(batch = "limits") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "limits") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * Станок смотрит на восток, у контроллера курса предел 30° влево и 30° вправо. Ближняя цель на севере (90° влево) -
     * вне сектора, дальняя почти по курсу - внутри: пушка бьёт дальнюю и ни разу не выходит за ±30°. Дальнюю убрали -
     * пушка стоит у левого предела, статус «вне сектора», по ближней не стреляет. Предел сняли - поворачивает и бьёт ближнюю.
     */
    @GameTest(template = ARENA, batch = "limits", timeoutTicks = 700)
    public static void controllerYawLimitKeepsSector(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = FcsGameTests.fcs(h, new BlockPos(6, 1, 28));
        SettingsGameTests.configure(be, st -> st.dropStatic = false);
        AimControllerBlockEntity yc = AimControllerBlockEntity.find(h.getLevel(), h.absolutePos(mount), false);
        if (yc == null) throw new GameTestAssertException("no yaw controller");
        yc.limits.set(30, 30);
        if (yc.limits.first() != 30 || yc.limits.second() != 30 || !yc.limits.limited()) h.fail("limits not set: " + yc.limits.formatValue());
        Mob near = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(10.5, 1, 17.5), 0, 0));
        Mob far = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 36.5), 0, 0));
        AtomicInteger nearHits = countHits(h, near), farHits = countHits(h, far);
        double[] maxRel = {0};
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            MountState m = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            if (m == null || m.mount == null || !m.mount.isAssembled()) return;
            double rel = Aim.wrap180(m.mount.yaw() - m.mount.baseYaw());          // + вправо
            switch (phase.get()) {
                case 0 -> {
                    maxRel[0] = Math.max(maxRel[0], Math.abs(rel));
                    if (Math.abs(rel) > 30.5) h.fail("mount left the sector: " + f(rel));
                    if (nearHits.get() > 0) h.fail("fired at the target outside the sector");
                    if (farHits.get() >= 2) {
                        far.discard();
                        since.set(t);
                        phase.set(1);
                    } else if (t > 300) {
                        h.fail("target inside the sector not hit: status " + m.status + ", rel " + f(rel));
                    }
                }
                case 1 -> {
                    if (Math.abs(rel) > 30.5) h.fail("mount left the sector: " + f(rel));
                    if (nearHits.get() > 0) h.fail("fired at the target outside the sector");
                    if (t - since.get() >= 60) {
                        // ближняя слева: пушка у левого предела (-30), вне сектора
                        if (m.status != Status.OUT_OF_ARC) h.fail("expected OUT_OF_ARC, got " + m.status);
                        if (rel > -29) h.fail("mount is not at the left limit: " + f(rel));
                        FcsMod.LOG.info("[radar_fcs test] yaw_limit: far hit {}, max |rel| {}, parked at {} with {}", farHits.get(), f(maxRel[0]), f(rel), m.status);
                        yc.limits.set(AngleLimit.FREE, AngleLimit.FREE);
                        since.set(t);
                        phase.set(2);
                    }
                }
                case 2 -> {
                    if (nearHits.get() >= 1) {
                        FcsMod.LOG.info("[radar_fcs test] yaw_limit: limit removed - near target hit after {} ticks, rel {}", t - since.get(), f(rel));
                        h.succeed();
                    } else if (t - since.get() > 200) {
                        h.fail("without the limit the near target is still not hit: status " + m.status + ", rel " + f(rel));
                    }
                }
                default -> { }
            }
        });
    }

    /**
     * Пределы у контроллера наклона: вверх 5°. Цель высоко (нужно ~25°) - ствол не выше 5°, «вне сектора», огня нет.
     */
    @GameTest(template = ARENA, batch = "limits", timeoutTicks = 300)
    public static void controllerPitchLimit(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = FcsGameTests.fcs(h, new BlockPos(6, 1, 28));
        SettingsGameTests.configure(be, st -> st.dropStatic = false);
        AimControllerBlockEntity pc = AimControllerBlockEntity.find(h.getLevel(), h.absolutePos(mount), true);
        if (pc == null) throw new GameTestAssertException("no pitch controller");
        pc.limits.set(5, AngleLimit.FREE);
        h.setBlock(new BlockPos(30, 11, 32), Blocks.STONE.defaultBlockState());
        Mob high = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(30.5, 12, 32.5), 0, 0));
        AtomicInteger hits = countHits(h, high);
        double[] maxPitch = {-90};
        h.onEachTick(() -> {
            MountState m = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            if (m == null || m.mount == null || !m.mount.isAssembled()) return;
            maxPitch[0] = Math.max(maxPitch[0], m.mount.pitch());
            if (m.mount.pitch() > 5.5) h.fail("barrel above the up limit: " + f(m.mount.pitch()));
            if (hits.get() > 0) h.fail("fired at a target above the limit");
            if (h.getTick() >= 200) {
                if (m.status != Status.OUT_OF_ARC) h.fail("expected OUT_OF_ARC, got " + m.status);
                if (maxPitch[0] < 4) h.fail("barrel did not rise to the limit: " + f(maxPitch[0]));
                FcsMod.LOG.info("[radar_fcs test] pitch_limit: max pitch {}, status {}", f(maxPitch[0]), m.status);
                h.succeed();
            }
        });
    }

    /**
     * Приводы орудия вместо контроллеров. У привода курса пределы влево 30°, вправо 20°: цель в ~38° правее начального
     * направления недостижима ни так, ни в обход - орудие стоит у правого предела, «вне сектора», огня нет. Вправо 60° -
     * попадает. (Без предела влево цель была бы достижима обходом на 322° влево - так и должно быть.)
     */
    @GameTest(template = ARENA, batch = "limits", timeoutTicks = 900)
    public static void gunDriveYawLimit(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28);
        autocannon(h, mount, Direction.EAST, 4, 64);
        h.setBlock(mount.below(), FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(DirectionalKineticBlock.FACING, Direction.UP).setValue(GunDriveBlock.PITCH, false));
        h.setBlock(mount.south(), FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(DirectionalKineticBlock.FACING, Direction.NORTH).setValue(GunDriveBlock.PITCH, true));
        UUID gun = UUID.randomUUID();
        TurretGameTests.gunLink(h, mount, Direction.NORTH, gun);
        TurretGameTests.gunLink(h, mount.below(), Direction.WEST, gun);
        TurretGameTests.gunLink(h, mount.south(), Direction.EAST, gun);
        FireControlBlockEntity be = FcsGameTests.fcs(h, fcsPos);
        SettingsGameTests.configure(be, st -> st.dropStatic = false);
        if (!(h.getLevel().getBlockEntity(h.absolutePos(mount.below())) instanceof GunDriveBlockEntity yawDrive))
            throw new GameTestAssertException("no yaw drive");
        yawDrive.limits.set(30, 20);                                             // ряд 0 - влево, 1 - вправо
        var target = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(28.5, 4, 46.5), 0, 0));
        h.setBlock(new BlockPos(28, 3, 46), Blocks.STONE.defaultBlockState());
        AtomicInteger hits = countHits(h, target);
        double[] maxRight = {0};
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            List<MountState> ms = be.mountStates();
            MountState m = ms.isEmpty() ? null : ms.get(0);
            if (m == null || m.mount == null || !m.mount.isAssembled()) return;
            double rel = Aim.wrap180(m.mount.yaw() - m.mount.baseYaw());          // + вправо
            if (phase.get() == 0) {
                maxRight[0] = Math.max(maxRight[0], rel);
                if (rel > 23 || rel < -33) h.fail("gun drive turned past a limit: " + f(rel));
                if (hits.get() > 0) h.fail("fired at a target outside the drive limit");
                if (t >= 260) {
                    if (m.status != Status.OUT_OF_ARC) h.fail("expected OUT_OF_ARC, got " + m.status + " at rel " + f(rel));
                    if (rel < 15) h.fail("gun stopped short of the limit: " + f(rel));
                    FcsMod.LOG.info("[radar_fcs test] drive_limit: parked at {} (max {}), status {}", f(rel), f(maxRight[0]), m.status);
                    yawDrive.limits.set(30, 60);
                    since.set(t);
                    phase.set(1);
                }
            } else if (hits.get() >= 2) {
                FcsMod.LOG.info("[radar_fcs test] drive_limit: limit 60 - hits {} after {} ticks, rel {}", hits.get(), t - since.get(), f(rel));
                h.succeed();
            } else if (t - since.get() > 400) {
                h.fail("with limit 60 the target is not hit: status " + m.status + ", rel " + f(rel));
            }
        });
    }
}
