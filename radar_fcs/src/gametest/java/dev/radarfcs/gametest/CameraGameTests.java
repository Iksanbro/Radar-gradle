package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.link;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.CameraBlock;
import dev.radarfcs.block.CameraBlockEntity;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.registry.FcsRegistry;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Камеры: ручное наведение пушек сети по взгляду камеры, огонь по команде, пауза автоприцеливания. */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class CameraGameTests {
    private static final String ARENA = "arena";

    @BeforeBatch(batch = "camera") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "camera") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }

    /**
     * Автопушка и камера в одной сети, две неподвижные цели. Без камеры пушка бьёт ближнюю. Игрок смотрит с камеры
     * на дальнюю без огня - выстрелов нет (автоприцеливание на паузе); с огнём - попадания только по дальней.
     * Управление снято - пушка снова сама бьёт ближнюю.
     */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 700)
    public static void cameraManualAimAndFire(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28), camPos = new BlockPos(8, 3, 30);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, fcsPos);
        SettingsGameTests.configure(be, s -> s.dropStatic = false);
        h.setBlock(camPos, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(camPos));
        link(h, camPos, Direction.UP, fcsPos);
        Mob near = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(26.5, 1, 32.5), 0, 0));
        Mob far = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(42.5, 1, 36.5), 0, 0));
        AtomicInteger nearHits = countHits(h, near), farHits = countHits(h, far);
        UUID who = UUID.randomUUID();
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger(), mark = new AtomicInteger(), markFar = new AtomicInteger();
        List<String> log = new ArrayList<>();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            if (!(h.getLevel().getBlockEntity(h.absolutePos(camPos)) instanceof CameraBlockEntity cam)) return;
            MountState ms = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            Vec3 eye = cam.eye(), aim = far.getBoundingBox().getCenter();
            Vec3 d = aim.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            switch (phase.get()) {
                case 0 -> {                                                   // автоприцеливание: ближняя
                    if (nearHits.get() >= 2) {
                        log.add("auto hit near " + nearHits.get() + " at t=" + t);
                        since.set(t);
                        phase.set(1);
                    }
                }
                case 1 -> {                                                   // камера на дальнюю, огня нет
                    cam.controlForTest(who, yaw, pitch, false);
                    if (t == since.get() + 20) {
                        mark.set(nearHits.get());
                        markFar.set(farHits.get());
                    }
                    if (t > since.get() + 20 && (nearHits.get() > mark.get() || farHits.get() > markFar.get())) {
                        h.fail("fired while the camera holds fire: near " + mark.get() + "->" + nearHits.get() + ", far " + farHits.get());
                    }
                    if (t == since.get() + 60) {
                        if (be.currentManualAim() == null) h.fail("no manual aim from the camera");
                        if (ms == null || ms.status != Status.CAMERA) h.fail("mount status " + (ms == null ? null : ms.status) + ", expected CAMERA");
                        log.add("held fire 40 ticks, status " + (ms == null ? null : ms.status));
                        since.set(t);
                        phase.set(2);
                    }
                }
                case 2 -> {                                                   // огонь с камеры: только дальняя
                    cam.controlForTest(who, yaw, pitch, true);
                    if (t == since.get() + 1) mark.set(nearHits.get());
                    if (t > since.get() + 25 && nearHits.get() > mark.get()) h.fail("near target hit while aiming from the camera at the far one");
                    if (farHits.get() >= markFar.get() + 3 && t > since.get() + 25) {
                        log.add("camera fire: far hits " + farHits.get() + " at t=" + t + ", near unchanged " + nearHits.get());
                        cam.stopControl(who);
                        since.set(t);
                        mark.set(nearHits.get());
                        phase.set(3);
                    }
                }
                case 3 -> {                                                   // управление снято: снова ближняя
                    if (nearHits.get() > mark.get() && t > since.get() + 5) {
                        log.add("auto resumed: near hit after " + (t - since.get()) + " ticks");
                        phase.set(4);
                    }
                }
                default -> { }
            }
        });
        h.succeedWhen(() -> {
            if (phase.get() < 4) throw new net.minecraft.gametest.framework.GameTestAssertException("phase " + phase.get() + ": " + log);
            FcsMod.LOG.info("[radar_fcs test] camera: {}", log);
        });
    }
    /**
     * Голова камеры: после управления камера сохраняет последний взгляд (курс, наклон) - и после выхода игрока,
     * и в сохранении блока; глаз камеры - центр шара-головы.
     */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 100)
    public static void cameraKeepsLastView(GameTestHelper h) {
        BlockPos camPos = new BlockPos(8, 3, 30);
        h.setBlock(camPos, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(camPos));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(camPos)) instanceof CameraBlockEntity cam)) throw new GameTestAssertException("no camera");
        float[] d = cam.view();
        if (Math.abs(d[0] - Direction.EAST.toYRot()) > 0.01 || Math.abs(d[1]) > 0.01) throw new GameTestAssertException("default view " + d[0] + " " + d[1]);
        UUID who = UUID.randomUUID();
        cam.controlForTest(who, -40f, 25f, false);
        cam.stopControl(who);
        float[] v = cam.view();
        if (Math.abs(v[0] + 40f) > 0.01 || Math.abs(v[1] - 25f) > 0.01) throw new GameTestAssertException("view not kept: " + v[0] + " " + v[1]);
        if (cam.eye().distanceTo(cam.pivot()) > 1e-6) throw new GameTestAssertException("eye is not the ball center: " + cam.eye());
        var tag = cam.saveWithoutMetadata(h.getLevel().registryAccess());
        if (!tag.contains("ViewYaw") || Math.abs(tag.getFloat("ViewPitch") - 25f) > 0.01) throw new GameTestAssertException("view not saved: " + tag);
        h.succeed();
    }
    /** Монитор камер тратит 64 FE/т за каждую камеру своей сети. */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 100)
    public static void cameraMonitorUsagePerCamera(GameTestHelper h) {
        BlockPos mon = new BlockPos(6, 1, 26), c1 = new BlockPos(8, 3, 30), c2 = new BlockPos(10, 3, 30);
        h.setBlock(mon, FcsRegistry.CAMERA_MONITOR.get().defaultBlockState());
        h.setBlock(c1, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.EAST));
        h.setBlock(c2, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.EAST));
        for (BlockPos p : List.of(mon, c1, c2)) FcsGameTests.CREATED.add(h.absolutePos(p));
        FcsGameTests.link(h, c1, Direction.UP, mon);
        FcsGameTests.link(h, c2, Direction.UP, mon);
        h.runAfterDelay(45, () -> {
            if (!(h.getLevel().getBlockEntity(h.absolutePos(mon)) instanceof dev.radarfcs.block.CameraMonitorBlockEntity m))
                throw new GameTestAssertException("no camera monitor");
            if (m.cameras() != 2 || m.usage() != 128) throw new GameTestAssertException("usage " + m.usage() + " for " + m.cameras() + " cameras");
            h.succeed();
        });
    }
}
