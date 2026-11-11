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
    /**
     * Камера смотрит на корабль Sable (летит туда-обратно): точка взгляда - на его блоках (луч переводится в
     * координаты корабля), со скоростью корабля; автопушка по ЛКМ бьёт корабль с упреждением.
     */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 400)
    public static void cameraAimsAtSableShip(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28), camPos = new BlockPos(8, 3, 30);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, fcsPos);
        h.setBlock(camPos, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(camPos));
        link(h, camPos, Direction.UP, fcsPos);
        Object sub = SableTestSupport.ship(h, new BlockPos(40, 3, 26), 3, new Vec3(0, 0, 0.12), 12, null);
        AtomicInteger hits = SableTestSupport.watchShipHits(h, sub, "camera_ship");
        UUID who = UUID.randomUUID();
        List<String> log = new ArrayList<>();
        boolean[] checked = {false};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            if (!(h.getLevel().getBlockEntity(h.absolutePos(camPos)) instanceof CameraBlockEntity cam)) return;
            var box = SableTestSupport.box(sub);
            Vec3 eye = cam.eye(), c = box.getCenter(), d = c.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            cam.controlForTest(who, yaw, pitch, t > 30);
            Vec3 aim = cam.aimPoint();
            if (aim == null || t < 10) return;
            if (!checked[0]) {
                // точка - на ближней стороне корабля, а не за ним (луч не прошёл насквозь)
                double toAim = aim.distanceTo(eye), toCenter = c.distanceTo(eye);
                if (toAim > toCenter || toAim < toCenter - 3) throw new GameTestAssertException("aim point not on the ship: aim "
                        + String.format(java.util.Locale.ROOT, "%.2f", toAim) + " center " + String.format(java.util.Locale.ROOT, "%.2f", toCenter));
                var ma = cam.manualAim(h.getLevel().getGameTime());
                if (ma == null || Math.abs(Math.abs(ma.velocity().z()) - 0.12) > 0.03)
                    throw new GameTestAssertException("ship velocity not passed: " + (ma == null ? null : ma.velocity()));
                log.add("aim on ship side at " + String.format(java.util.Locale.ROOT, "%.1f", toAim) + " (center " + String.format(java.util.Locale.ROOT, "%.1f", toCenter)
                        + "), velocity " + ma.velocity());
                checked[0] = true;
            }
            if (hits.get() >= 3) {
                log.add("ship hits " + hits.get() + " at t=" + t);
                FcsMod.LOG.info("[radar_fcs test] camera_ship: {}", log);
                h.succeed();
            } else if (t > 380) {
                throw new GameTestAssertException("camera fire did not hit the ship: hits " + hits.get() + " " + log);
            }
        });
    }
    /**
     * Камера без радарного линка (как фабричный датчик Create): предмет настраивается ПКМ по радарному линку, камера
     * из такого предмета сразу в сети - монитор камер её видит, вычислитель наводит по ней; сеть уходит в выпавший
     * предмет, снятая камера выходит из сети.
     */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 120)
    public static void cameraJoinsNetworkWithoutLink(GameTestHelper h) {
        BlockPos fcsPos = new BlockPos(6, 1, 28), mon = new BlockPos(6, 1, 24), camPos = new BlockPos(8, 3, 30);
        FireControlBlockEntity be = FcsGameTests.fcsBare(h, fcsPos);
        h.setBlock(mon, FcsRegistry.CAMERA_MONITOR.get().defaultBlockState());
        FcsGameTests.CREATED.add(h.absolutePos(mon));
        var link = link(h, mon, Direction.UP, fcsPos);
        java.util.UUID netId = link.network();
        // предмет камеры по радарному линку - настроен на его сеть
        if (!netId.equals(dev.radarfcs.item.CameraItem.tunableNetwork(h.getLevel(), link.getBlockPos())))
            throw new GameTestAssertException("radar link is not a tuning target");
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(FcsRegistry.CAMERA_ITEM.get());
        dev.radarfcs.item.RadarLinkItem.tune(stack, netId);
        h.setBlock(camPos, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(camPos));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(camPos)) instanceof CameraBlockEntity cam)) throw new GameTestAssertException("no camera");
        cam.applyComponentsFromItemStack(stack);                       // как при установке предметом
        if (!netId.equals(cam.network())) throw new GameTestAssertException("camera did not take the item network");
        UUID who = UUID.randomUUID();
        h.runAfterDelay(45, () -> {
            var cams = dev.radarfcs.net.CameraPayloads.cameras(h.getLevel(), h.absolutePos(mon));
            if (cams.size() != 1 || cams.get(0) != cam) throw new GameTestAssertException("monitor does not see the linkless camera: " + cams.size());
            cam.controlForTest(who, -90f, 0f, false);
            if (be.currentManualAim() == null) throw new GameTestAssertException("fire control does not see the linkless camera");
            var data = cam.collectComponents().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if (data == null || !netId.equals(data.copyTag().getUUID("RadarNetwork"))) throw new GameTestAssertException("network not kept for the drop");
            cam.stopControl(who);
            h.setBlock(camPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        });
        h.runAfterDelay(50, () -> {
            if (!dev.radarfcs.net.CameraPayloads.cameras(h.getLevel(), h.absolutePos(mon)).isEmpty())
                throw new GameTestAssertException("removed camera still in the network");
            h.succeed();
        });
    }
    /**
     * Орудия камеры (монитор камер): камера, которой назначена одна пушка, управляет только ею - вторая остаётся на
     * автоприцеле; без назначения камера управляет всеми. Набор сохраняется в камере.
     */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 120)
    public static void cameraControlsChosenGuns(GameTestHelper h) {
        floor(h);
        BlockPos a = new BlockPos(10, 1, 24), b = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28), camPos = new BlockPos(8, 3, 30);
        autocannon(h, a, Direction.EAST, 4, 64);
        autocannon(h, b, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, fcsPos);
        h.setBlock(camPos, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(camPos));
        link(h, camPos, Direction.UP, fcsPos);
        UUID who = UUID.randomUUID();
        h.runAfterDelay(30, () -> {
            if (!(h.getLevel().getBlockEntity(h.absolutePos(camPos)) instanceof CameraBlockEntity cam)) throw new GameTestAssertException("no camera");
            MountState ma = null, mb = null;
            for (MountState m : be.mountStates()) {
                if (m.pos.equals(h.absolutePos(a))) ma = m;
                if (m.pos.equals(h.absolutePos(b))) mb = m;
            }
            if (ma == null || mb == null) throw new GameTestAssertException("mounts not discovered");
            cam.setGuns(List.of(h.absolutePos(a).asLong()));
            cam.controlForTest(who, -90f, 0f, false);
            if (be.manualAimFor(ma) == null || be.manualAimFor(mb) != null) throw new GameTestAssertException("chosen guns not respected");
            if (!be.gunLabels(h.getLevel()).containsKey(h.absolutePos(b).asLong())) throw new GameTestAssertException("gun list misses a mount");
            var tag = cam.saveWithoutMetadata(h.getLevel().registryAccess());
            if (tag.getLongArray("Guns").length != 1) throw new GameTestAssertException("guns not saved");
            cam.setGuns(List.of());
            if (be.manualAimFor(ma) == null || be.manualAimFor(mb) == null) throw new GameTestAssertException("empty set must control all guns");
            cam.stopControl(who);
            h.succeed();
        });
    }

    /**
     * Камера смотрит на само орудие (станок своей пушки): пушка не поворачивается (раньше она «гонялась» за точкой
     * взгляда, которая уезжала вместе с ней), статус CAMERA_SELF. Взгляд в сторону - пушка снова наводится.
     */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 300)
    public static void cameraLookingAtOwnGunHolds(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28), camPos = new BlockPos(15, 3, 27);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, fcsPos);
        h.setBlock(camPos, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.WEST));
        FcsGameTests.CREATED.add(h.absolutePos(camPos));
        link(h, camPos, Direction.UP, fcsPos);
        UUID who = UUID.randomUUID();
        Vec3 self = h.absoluteVec(new Vec3(10.5, 1.5, 32.5));                    // станок своей пушки
        Vec3 away = h.absoluteVec(new Vec3(30.5, 0.9, 20.5));                    // земля в стороне
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        double[] yaw0 = {Double.NaN};
        List<String> log = new ArrayList<>();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            if (!(h.getLevel().getBlockEntity(h.absolutePos(camPos)) instanceof CameraBlockEntity cam)) return;
            MountState ms = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            if (ms == null || ms.mount == null || !ms.mount.isAssembled()) return;
            Vec3 d = (phase.get() < 2 ? self : away).subtract(cam.eye());
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            cam.controlForTest(who, yaw, pitch, false);
            switch (phase.get()) {
                case 0 -> {
                    since.set(t);
                    phase.set(1);
                }
                case 1 -> {                                                   // взгляд на свой станок
                    if (t == since.get() + 5) yaw0[0] = ms.mount.yaw();
                    if (t > since.get() + 5 && Math.abs(ms.mount.yaw() - yaw0[0]) > 0.01) {
                        h.fail("gun turned while the camera looks at it: " + yaw0[0] + " -> " + ms.mount.yaw() + ", status " + ms.status);
                    }
                    if (t == since.get() + 45) {
                        if (ms.status != Status.CAMERA_SELF) h.fail("status " + ms.status + ", expected CAMERA_SELF");
                        log.add("held " + yaw0[0] + " for 40 ticks, status " + ms.status);
                        since.set(t);
                        phase.set(2);
                    }
                }
                case 2 -> {                                                   // взгляд в сторону - наводится
                    if (Math.abs(net.minecraft.util.Mth.wrapDegrees(ms.mount.yaw() - yaw0[0])) > 20 && ms.status != Status.CAMERA_SELF) {
                        log.add("looked away: turned to " + String.format(java.util.Locale.ROOT, "%.1f", ms.mount.yaw())
                                + " after " + (t - since.get()) + " ticks, status " + ms.status);
                        phase.set(3);
                    } else if (t > since.get() + 80) {
                        h.fail("gun did not follow the camera after looking away: yaw " + ms.mount.yaw() + ", status " + ms.status);
                    }
                }
                default -> { }
            }
        });
        h.succeedWhen(() -> {
            if (phase.get() < 3) throw new GameTestAssertException("phase " + phase.get());
            cleanupCamera(h, camPos, who);
            FcsMod.LOG.info("[radar_fcs test] camera_self: {}", log);
        });
    }

    /**
     * Камера на самом орудии (на его поворотной постройке Sable A с прицелом) смотрит на соседнюю постройку B того же
     * орудия: луч проходит сквозь своё орудие (точка взгляда - далеко за B), орудие не стоит в CAMERA_SELF и не
     * «гоняется» за точкой на себе. Камера на земле, смотрящая на B, - орудие стоит (CAMERA_SELF).
     */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 300)
    public static void cameraOnGunLooksThroughOwnGun(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        BlockPos fcsPos = new BlockPos(6, 1, 28);
        FireControlBlockEntity be = FcsGameTests.fcsBare(h, fcsPos);
        UUID radarNet = UUID.randomUUID(), gunNet = UUID.randomUUID();
        FcsGameTests.placeLink(h, fcsPos, Direction.UP, radarNet);
        // привод курса на земле (основание): орудийный и радарный линки
        BlockPos drive = new BlockPos(18, 1, 30);
        h.setBlock(drive, FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.UP));
        FcsGameTests.CREATED.add(h.absolutePos(drive));
        TurretGameTests.gunLink(h, drive, Direction.WEST, gunNet);
        FcsGameTests.placeLink(h, drive, Direction.NORTH, radarNet);
        // постройка A: прицел (с орудийным линком) и камера на орудии
        for (int x = 20; x <= 21; x++) for (int z = 30; z <= 31; z++) h.setBlock(new BlockPos(x, 2, z), net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState());
        h.setBlock(new BlockPos(20, 3, 30), FcsRegistry.SIGHT.get().defaultBlockState().setValue(dev.radarfcs.block.SightBlock.FACING, Direction.EAST));
        TurretGameTests.gunLink(h, new BlockPos(20, 3, 30), Direction.UP, gunNet);
        BlockPos camRel = new BlockPos(21, 3, 31);
        h.setBlock(camRel, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.UP));
        if (h.getLevel().getBlockEntity(h.absolutePos(camRel)) instanceof CameraBlockEntity c) c.setNetwork(radarNet);
        // постройка B вплотную к A: другая поворотная часть того же орудия (стенка перед камерой)
        for (int x = 22; x <= 23; x++) for (int y = 2; y <= 4; y++) for (int z = 30; z <= 31; z++)
            h.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState());
        Object a = SableTestSupport.staticSub(h, new BlockPos(20, 2, 30), new BlockPos(21, 4, 31));
        Object b = SableTestSupport.staticSub(h, new BlockPos(22, 2, 30), new BlockPos(23, 4, 31));
        UUID bId = SableTestSupport.subId(b);
        // камера на земле - для второй половины
        BlockPos groundCam = new BlockPos(14, 3, 34);
        h.setBlock(groundCam, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.UP));
        FcsGameTests.CREATED.add(h.absolutePos(groundCam));
        if (h.getLevel().getBlockEntity(h.absolutePos(groundCam)) instanceof CameraBlockEntity c) c.setNetwork(radarNet);
        UUID who = UUID.randomUUID();
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        List<String> log = new ArrayList<>();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            if (t < 25) return;
            CameraBlockEntity onGun = null;
            for (BlockPos p : dev.radarfcs.link.LinkRegistry.members(h.getLevel(), radarNet)) {
                if (h.getLevel().getBlockEntity(p) instanceof CameraBlockEntity c && dev.radarfcs.compat.SableCompat.containingId(h.getLevel(), p) != null) onGun = c;
            }
            MountState ms = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            Vec3 bCenter = SableTestSupport.box(b).getCenter();
            switch (phase.get()) {
                case 0 -> {                                                   // камера на орудии смотрит в B
                    if (onGun == null) {
                        if (t > 120) h.fail("camera on the gun is not in the network");
                        return;
                    }
                    Vec3 d = bCenter.subtract(onGun.eye());
                    onGun.controlForTest(who, (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                            (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))), false);
                    if (since.get() == 0) since.set(t);
                    if (t - since.get() < 20) return;
                    Vec3 aim = onGun.aimPoint();
                    var ma = onGun.manualAim(h.getLevel().getGameTime());
                    if (ms == null || aim == null || ma == null) h.fail("no turret state or aim: " + ms + " " + aim);
                    double dist = aim.distanceTo(onGun.eye());
                    if (ms.status == Status.CAMERA_SELF) h.fail("gun froze: the camera on it looks at its own part");
                    if (bId.equals(ma.hit().ship()) || dist < 6) h.fail("ray stopped on the gun's own structure: dist "
                            + String.format(java.util.Locale.ROOT, "%.1f", dist) + " hit " + ma.hit());
                    log.add("camera on the gun looks through its gun: aim " + String.format(java.util.Locale.ROOT, "%.1f", dist)
                            + " blocks away, status " + ms.status);
                    onGun.stopControl(who);
                    since.set(t);
                    phase.set(1);
                }
                case 1 -> {                                                   // камера на земле смотрит на B
                    if (!(h.getLevel().getBlockEntity(h.absolutePos(groundCam)) instanceof CameraBlockEntity g)) return;
                    Vec3 d = bCenter.subtract(g.eye());
                    g.controlForTest(who, (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                            (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))), false);
                    if (t - since.get() < 20) return;
                    if (ms == null || ms.status != Status.CAMERA_SELF) h.fail("ground camera looks at the gun: status "
                            + (ms == null ? null : ms.status) + ", hit " + (g.manualAim(h.getLevel().getGameTime()) == null ? null
                            : g.manualAim(h.getLevel().getGameTime()).hit()));
                    log.add("ground camera at the gun: status " + ms.status);
                    g.stopControl(who);
                    phase.set(2);
                }
                default -> { }
            }
        });
        h.succeedWhen(() -> {
            if (phase.get() < 2) throw new GameTestAssertException("phase " + phase.get());
            FcsMod.LOG.info("[radar_fcs test] camera_on_gun: {} (sub-level A {})", log, SableTestSupport.subId(a));
        });
    }

    /**
     * Пауза в командах камеры (клиент завис, лаг сети) не отнимает управление насовсем. Пока команд нет дольше
     * TIMEOUT, камера свободна и пушки на автоприцеле; первая же команда того же игрока управление продолжает -
     * раньше она молча отбрасывалась, и пушки не слушались камеру до перезахода в неё. Если за время паузы камеру
     * занял другой игрок, прежнему отказ.
     */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 300)
    public static void cameraControlResumesAfterPause(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28), camPos = new BlockPos(8, 3, 30);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, fcsPos);
        SettingsGameTests.configure(be, s -> s.dropStatic = false);
        h.setBlock(camPos, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(camPos));
        link(h, camPos, Direction.UP, fcsPos);
        Mob far = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(42.5, 1, 36.5), 0, 0));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        BlockPos monitor = h.absolutePos(fcsPos);                         // место монитора для проверки не важно
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        List<String> log = new ArrayList<>();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            if (!(h.getLevel().getBlockEntity(h.absolutePos(camPos)) instanceof CameraBlockEntity cam)) return;
            long now = h.getLevel().getGameTime();
            Vec3 d = far.getBoundingBox().getCenter().subtract(cam.eye());
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            // как обработчик пакета Control: команда принимается, только если игрок может продолжать управление
            Runnable command = () -> {
                if (cam.acceptsControl(a)) cam.control(null, yaw, pitch, false);
            };
            switch (phase.get()) {
                case 0 -> {
                    if (t < 20) return;                                       // пушка собралась
                    if (!cam.startControl(a, monitor, now)) h.fail("camera refused the first controller");
                    since.set(t);
                    phase.set(1);
                }
                case 1 -> {                                                   // обычное управление
                    command.run();
                    if (t < since.get() + 15) return;
                    if (!a.equals(cam.controller(now)) || be.currentManualAim() == null) h.fail("camera is not controlled: " + cam.controller(now));
                    if (cam.startControl(b, monitor, now)) h.fail("another player took the camera while it was controlled");
                    log.add("controlled, manual aim present, busy for others");
                    since.set(t);
                    phase.set(2);
                }
                case 2 -> {                                                   // пауза: команд нет 30 тиков
                    if (t < since.get() + 30) return;
                    if (cam.controller(now) != null) h.fail("camera still reports a controller after a 30 tick pause");
                    if (be.currentManualAim() != null) h.fail("manual aim is still active during the pause");
                    if (!cam.acceptsControl(a)) h.fail("the paused controller is rejected: control would be lost until re-entering");
                    log.add("pause 30 ticks: camera free, guns on auto");
                    since.set(t);
                    phase.set(3);
                }
                case 3 -> {                                                   // команды снова идут - управление продолжается
                    command.run();
                    if (t < since.get() + 5) return;
                    MountState ms = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
                    if (!a.equals(cam.controller(now)) || be.currentManualAim() == null) h.fail("control did not resume after the pause");
                    if (ms == null || ms.status != Status.CAMERA) h.fail("gun does not follow the camera after the pause: " + (ms == null ? null : ms.status));
                    log.add("resumed without re-entering, status " + ms.status);
                    since.set(t);
                    phase.set(4);
                }
                case 4 -> {                                                   // снова пауза, камеру занимает другой игрок
                    if (t < since.get() + 20) return;
                    if (!cam.startControl(b, monitor, now)) h.fail("a free camera refused another player");
                    if (cam.acceptsControl(a)) h.fail("the old controller is still accepted after another player took the camera");
                    if (!cam.acceptsControl(b)) h.fail("the new controller is rejected");
                    cam.stopControl(b);
                    log.add("another player took the free camera, the old one is refused");
                    phase.set(5);
                }
                default -> { }
            }
        });
        h.succeedWhen(() -> {
            if (phase.get() < 5) throw new GameTestAssertException("phase " + phase.get());
            FcsMod.LOG.info("[radar_fcs test] camera_resume: {}", log);
        });
    }

    /**
     * Луч взгляда через постройку Sable не должен попадать в переписанный Sable {@code Level.clip} в координатах плота:
     * тот переводит в мир только конец луча, лежащий в плоте, и луч становится длиной в миллионы блоков (у клиента
     * кадр считался секундами, сервер завис бы на загрузке чанков). {@code clipShips} обязан отработать быстро и дать
     * точку на постройке, даже если ему передали сам уровень; лучи со смешанными координатами - отказ, а не трассировка.
     */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 200)
    public static void shipRayNeverGoesThroughSableClip(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        for (int x = 30; x <= 32; x++) for (int y = 3; y <= 5; y++) for (int z = 30; z <= 32; z++)
            h.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState());
        Object sub = SableTestSupport.staticSub(h, new BlockPos(30, 3, 30), new BlockPos(32, 5, 32));
        UUID id = SableTestSupport.subId(sub);
        h.runAtTickTime(10, () -> {
            var level = h.getLevel();
            var box = SableTestSupport.box(sub);
            Vec3 c = box.getCenter();
            // взгляд с 4 блоков от постройки на RANGE блоков вперёд - как у камеры рядом с частью орудия
            Vec3 from = new Vec3(box.minX - 4, c.y, c.z), to = from.add(CameraBlockEntity.RANGE, 0, 0);
            long t0 = System.nanoTime();
            var hit = dev.radarfcs.compat.SableCompat.clipShips(level, from, to, x -> false, level, false);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ms > 2000) h.fail("ray through a Sable structure took " + ms + " ms");
            if (hit == null || !id.equals(hit.ship())) h.fail("ray missed the structure: " + hit);
            if (Math.abs(hit.point().x - box.minX) > 0.6 || hit.point().distanceTo(c) > 3) h.fail("hit point is not on the near face: " + hit.point() + " box " + box);
            // блоки мира под лучом: точка - в мировых координатах, постройки в неё не входят
            Vec3 down = dev.radarfcs.compat.SableCompat.clipWorld(level, new Vec3(c.x, c.y + 10, c.z), new Vec3(c.x, c.y - 20, c.z));
            if (down == null || Math.abs(down.x - c.x) > 0.01 || down.y > box.minY) h.fail("world ray stopped on the structure or missed the floor: " + down);
            // концы луча в разных системах координат (мир и плот) - не трассировать
            Vec3 plot = Vec3.atCenterOf(new BlockPos(20_480_000, 100, 20_480_000));
            t0 = System.nanoTime();
            var bad = dev.radarfcs.compat.SableCompat.clipShips(level, from, plot, x -> false, level, false);
            Vec3 badWorld = dev.radarfcs.compat.SableCompat.clipWorld(level, from, plot);
            long ms2 = (System.nanoTime() - t0) / 1_000_000;
            if (bad != null || badWorld != null || ms2 > 500) h.fail("a ray across coordinate systems was traced: " + bad + " " + badWorld + " in " + ms2 + " ms");
            FcsMod.LOG.info("[radar_fcs test] ship_ray: hit {} in {} ms, mixed-coordinate ray refused in {} ms", hit.point(), ms, ms2);
            h.succeed();
        });
    }

    /**
     * Лучи аппаратуры (взгляд камеры, проверка линии огня) не грузят чанки. Обычный {@code Level.getBlockState} на
     * сервере догружает и генерирует чанк прямо в тике: дальняя точка наведения с камеры (небо, горизонт - траектория на
     * сотни блоков) вешала сервер на секунды, и клиент на это время терял управление камерой.
     */
    @GameTest(template = ARENA, batch = "camera", timeoutTicks = 100)
    public static void raysDoNotLoadChunks(GameTestHelper h) {
        floor(h);
        BlockPos camPos = new BlockPos(8, 3, 30);
        h.setBlock(camPos, FcsRegistry.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(camPos));
        UUID who = UUID.randomUUID();
        h.runAtTickTime(5, () -> {
            ServerLevel level = h.getLevel();
            var chunks = level.getChunkSource();
            // луч целиком в 5000 блоках от площадок тестов: там ничего не загружено
            BlockPos o = h.absolutePos(camPos).offset(5000, 0, 5000);
            Vec3 from = new Vec3(o.getX() + 0.5, 200, o.getZ() + 0.5), to = from.add(600, -260, 150);
            List<int[]> far = new ArrayList<>();
            List<dev.radarfcs.core.Vec> path = new ArrayList<>();
            for (int i = 0; i <= 40; i++) {
                Vec3 q = from.lerp(to, i / 40.0);
                far.add(new int[] {net.minecraft.util.Mth.floor(q.x) >> 4, net.minecraft.util.Mth.floor(q.z) >> 4});
                path.add(new dev.radarfcs.core.Vec(q.x, q.y, q.z));
            }
            for (int[] c : far) {
                if (chunks.getChunkNow(c[0], c[1]) != null) h.fail("chunk " + c[0] + ", " + c[1] + " far from the tests is loaded before the rays");
            }
            long t0 = System.nanoTime();
            Vec3 hit = dev.radarfcs.compat.SableCompat.clipWorld(level, from, to);
            boolean air = dev.radarfcs.compat.SableCompat.plain(level).getBlockState(BlockPos.containing(to)).isAir();
            var target = dev.radarfcs.core.Track.point("far", "far", path.get(path.size() - 1), level.getGameTime());
            var verdict = dev.radarfcs.fcs.FireSafety.check(level, path, List.of(), target, dev.radarfcs.core.IffRule.DEFAULT, java.util.Set.of());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (hit != null || !air) h.fail("a chunk that is not loaded must read as empty: hit " + hit + ", air " + air);
            if (verdict != dev.radarfcs.fcs.FireSafety.Verdict.CLEAR) h.fail("line of fire over unloaded chunks: " + verdict);
            for (int[] c : far) {
                if (chunks.getChunkNow(c[0], c[1]) != null || chunks.hasChunk(c[0], c[1])) h.fail("a ray loaded chunk " + c[0] + ", " + c[1]);
            }
            if (ms > 1000) h.fail("rays over unloaded chunks took " + ms + " ms");

            // взгляд камеры во все стороны на всю дальность: чанки, не загруженные до взгляда, не загружены и после
            if (!(level.getBlockEntity(h.absolutePos(camPos)) instanceof CameraBlockEntity cam)) {
                h.fail("no camera");
                return;
            }
            int unloaded = 0;
            t0 = System.nanoTime();
            for (float yaw : new float[] {-90, 90, 0, 180}) {
                float pitch = -20;
                Vec3 eye = cam.eye(), dir = Vec3.directionFromRotation(pitch, yaw);
                List<int[]> before = new ArrayList<>();
                for (int d = 0; d <= CameraBlockEntity.RANGE; d += 8) {
                    Vec3 q = eye.add(dir.scale(d));
                    int cx = net.minecraft.util.Mth.floor(q.x) >> 4, cz = net.minecraft.util.Mth.floor(q.z) >> 4;
                    if (chunks.getChunkNow(cx, cz) == null && !chunks.hasChunk(cx, cz)) before.add(new int[] {cx, cz});
                }
                cam.controlForTest(who, yaw, pitch, false);
                if (cam.aimPoint() == null) h.fail("no look point at yaw " + yaw);
                for (int[] c : before) {
                    if (chunks.getChunkNow(c[0], c[1]) != null || chunks.hasChunk(c[0], c[1])) h.fail("the camera look ray loaded chunk " + c[0] + ", " + c[1]);
                }
                unloaded += before.size();
            }
            long ms2 = (System.nanoTime() - t0) / 1_000_000;
            if (unloaded == 0) h.fail("no unloaded chunks around the test: the camera part proves nothing");
            if (ms2 > 1000) h.fail("camera look rays took " + ms2 + " ms");
            FcsMod.LOG.info("[radar_fcs test] rays_no_chunk_load: far rays {} ms, camera rays {} ms over {} unloaded chunk samples", ms, ms2, unloaded);
            cleanupCamera(h, camPos, who);
            h.succeed();
        });
    }

    private static void cleanupCamera(GameTestHelper h, BlockPos camPos, UUID who) {
        if (h.getLevel().getBlockEntity(h.absolutePos(camPos)) instanceof CameraBlockEntity cam) cam.stopControl(who);
    }
}
