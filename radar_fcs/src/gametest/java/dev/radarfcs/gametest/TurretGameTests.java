package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.fcsBare;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.link;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.GunDriveBlock;
import dev.radarfcs.block.GunLinkBlockEntity;
import dev.radarfcs.block.RadarLinkBlock;
import dev.radarfcs.block.SightBlock;
import dev.radarfcs.block.SightBlockEntity;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.link.GunLinks;
import dev.radarfcs.link.LinkKind;
import dev.radarfcs.registry.FcsRegistry;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Орудия на вращателях: орудийная сеть (прицел, вращатели, станки CBC), обнаружение орудия вычислителем по радарному
 * линку на любой его части. Физика поворота Sable проверяется в dev-клиенте (DevScreenshots, -PfcsTurretOnly).
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class TurretGameTests {
    private static final String ARENA = "arena";

    @BeforeBatch(batch = "turret") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "turret") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }

    static GunLinkBlockEntity gunLink(GameTestHelper h, BlockPos attachedRel, Direction face, UUID net) {
        BlockPos at = attachedRel.relative(face);
        h.setBlock(at, FcsRegistry.GUN_LINK.get().defaultBlockState().setValue(RadarLinkBlock.FACING, face));
        FcsGameTests.CREATED.add(h.absolutePos(at));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(at)) instanceof GunLinkBlockEntity be)) {
            throw new GameTestAssertException("no gun link at " + at);
        }
        be.setNetwork(net);
        return be;
    }

    /**
     * Прицел, вращатель и автопушка. Радарный линк на прицеле: вычислитель видит орудие (одно состояние на прицеле,
     * вращатель не собран). Станок в орудийной сети - орудие стреляет им (состояние на станке, его углы вычислитель
     * не трогает). Прицел без орудийного линка - орудие без вращателей.
     */
    @GameTest(template = ARENA, batch = "turret", timeoutTicks = 400)
    public static void gunTurretGrouping(GameTestHelper h) {
        floor(h);
        BlockPos fcsPos = new BlockPos(6, 1, 28), rot = new BlockPos(10, 1, 30), sight = new BlockPos(12, 1, 30),
                mount = new BlockPos(10, 1, 36);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcsBare(h, fcsPos);
        h.setBlock(rot, FcsRegistry.GUN_ROTATOR.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP));
        h.setBlock(sight, FcsRegistry.SIGHT.get().defaultBlockState().setValue(SightBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(rot));
        FcsGameTests.CREATED.add(h.absolutePos(sight));
        UUID gun = UUID.randomUUID();
        gunLink(h, rot, Direction.WEST, gun);
        gunLink(h, sight, Direction.UP, gun);
        link(h, sight, Direction.NORTH, fcsPos);
        SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(30.5, 1, 30.5), 0, 0));
        BlockPos sAbs = h.absolutePos(sight), mAbs = h.absolutePos(mount), rAbs = h.absolutePos(rot);
        if (LinkKind.at(h.getLevel(), sAbs) != LinkKind.SIGHT || LinkKind.at(h.getLevel(), rAbs) != LinkKind.GUN_ROTATOR) {
            throw new GameTestAssertException("sight/rotator are not link equipment");
        }
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        float[] yaw0 = {Float.NaN};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            List<MountState> ms = be.mountStates();
            switch (phase.get()) {
                case 0 -> {                                         // орудие без станка: состояние на прицеле
                    if (t < 30) return;
                    List<BlockPos> group = GunLinks.group(h.getLevel(), sAbs);
                    if (!group.contains(rAbs)) throw new GameTestAssertException("gun group without rotator: " + group);
                    if (ms.size() == 1 && ms.get(0).pos.equals(sAbs) && ms.get(0).turret != null && ms.get(0).mount == null
                            && ms.get(0).turret.rotators.contains(rAbs) && ms.get(0).status == Status.NOT_ASSEMBLED) {
                        if (h.getLevel().getBlockEntity(sAbs) instanceof SightBlockEntity s && s.firing()) {
                            throw new GameTestAssertException("sight fires without rotators");
                        }
                        gunLink(h, mount, Direction.NORTH, gun);   // станок в орудие
                        be.requestDiscover();
                        since.set(t);
                        phase.set(1);
                    } else if (t > 120) {
                        throw new GameTestAssertException("turret not discovered: " + describe(ms));
                    }
                }
                case 1 -> {                                         // станок в орудии: состояние на станке
                    MountState m = ms.size() == 1 ? ms.get(0) : null;
                    if (m != null && m.pos.equals(mAbs) && m.turret != null && m.mount != null && m.mount.isAssembled()
                            && m.status == Status.NOT_ASSEMBLED) {
                        if (Float.isNaN(yaw0[0])) {
                            yaw0[0] = (float) m.mount.yaw();
                            since.set(t);
                        } else if (t - since.get() >= 40) {
                            // цель есть, но станок орудия не поворачивается сам - наводят вращатели
                            if (Math.abs(m.mount.yaw() - yaw0[0]) > 0.01) {
                                throw new GameTestAssertException("turret mount was turned: " + yaw0[0] + " -> " + m.mount.yaw());
                            }
                            h.setBlock(sight.above(), Blocks.AIR.defaultBlockState());   // прицел из орудия
                            be.requestDiscover();
                            since.set(t);
                            phase.set(2);
                        }
                    } else if (t - since.get() > 150) {
                        throw new GameTestAssertException("mount not part of the turret: " + describe(ms));
                    }
                }
                case 2 -> {                                         // прицел сам по себе: без вращателей
                    if (ms.size() == 1 && ms.get(0).pos.equals(sAbs) && ms.get(0).status == Status.TURRET_NO_ROTATOR) {
                        h.succeed();
                    } else if (t - since.get() > 100) {
                        throw new GameTestAssertException("lone sight state wrong: " + describe(ms));
                    }
                }
                default -> { }
            }
        });
    }

    /**
     * Автопушка без контроллеров наводки: её валы крутят приводы орудия (курс - снизу, наклон - сбоку) от моторов
     * 256 об/мин. Станок и приводы - в одной орудийной сети, прицела нет: наводится ствол. Вычислитель должен довести
     * ствол валами CBC до цели (передаточное число учится) и попасть.
     */
    @GameTest(template = ARENA, batch = "turret", timeoutTicks = 600)
    public static void gunDrivesTurnMountByShafts(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28);
        autocannon(h, mount, Direction.EAST, 4, 64);
        // контроллеры наводки -> приводы орудия (моторы на тех же валах остаются)
        h.setBlock(mount.below(), FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(DirectionalKineticBlock.FACING, Direction.UP).setValue(GunDriveBlock.PITCH, false));
        h.setBlock(mount.south(), FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(DirectionalKineticBlock.FACING, Direction.NORTH).setValue(GunDriveBlock.PITCH, true));
        UUID gun = UUID.randomUUID();
        gunLink(h, mount, Direction.NORTH, gun);
        gunLink(h, mount.below(), Direction.WEST, gun);
        gunLink(h, mount.south(), Direction.EAST, gun);
        FireControlBlockEntity be = FcsGameTests.fcs(h, fcsPos);
        SettingsGameTests.configure(be, st -> st.dropStatic = false);
        // цель сбоку и выше: нужен и поворот, и подъём ствола
        var target = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(28.5, 4, 46.5), 0, 0));
        h.setBlock(new BlockPos(28, 3, 46), Blocks.STONE.defaultBlockState());
        AtomicInteger hits = FcsGameTests.countHits(h, target);
        BlockPos mAbs = h.absolutePos(mount);
        float[] yaw0 = {Float.NaN};
        h.onEachTick(() -> {
            List<MountState> ms = be.mountStates();
            MountState m = ms.isEmpty() ? null : ms.get(0);
            if (m == null || m.mount == null || !m.mount.isAssembled()) return;
            if (m.turret == null || !m.pos.equals(mAbs)) throw new GameTestAssertException("mount is not a gun with drives: " + describe(ms));
            if (Float.isNaN(yaw0[0])) yaw0[0] = (float) m.mount.yaw();
            if (hits.get() >= 3) {
                FcsMod.LOG.info("[radar_fcs test] gun_drives: hits {} at t={}, yaw {} -> {}, pitch {}, status {}, miss {}", hits.get(),
                        h.getTick(), yaw0[0], String.format(java.util.Locale.ROOT, "%.1f", m.mount.yaw()),
                        String.format(java.util.Locale.ROOT, "%.1f", m.mount.pitch()), m.status,
                        String.format(java.util.Locale.ROOT, "%.2f", m.predictedMiss));
                h.succeed();
            } else if (h.getTick() > 560) {
                throw new GameTestAssertException("gun drives did not bring the barrel on target: hits " + hits.get() + ", status "
                        + m.status + ", yaw " + m.mount.yaw() + ", pitch " + m.mount.pitch() + ", err " + m.aimError);
            }
        });
    }

    /**
     * Настройки орудия (Shift+ПКМ по орудийному линку): автоприцел выключен - орудие не ведёт цель и не стреляет
     * (статус «только камера»); стрельба выключена - наводится, но не стреляет; всё включено - попадает.
     * Настройки расходятся на все линки сети, имя - в сводке вычислителя.
     */
    @GameTest(template = ARENA, batch = "turret", timeoutTicks = 700)
    public static void gunSettingsAutoAimAndFire(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28);
        autocannon(h, mount, Direction.EAST, 4, 64);
        h.setBlock(mount.below(), FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(DirectionalKineticBlock.FACING, Direction.UP).setValue(GunDriveBlock.PITCH, false));
        h.setBlock(mount.south(), FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(DirectionalKineticBlock.FACING, Direction.NORTH).setValue(GunDriveBlock.PITCH, true));
        UUID gun = UUID.randomUUID();
        GunLinkBlockEntity l1 = gunLink(h, mount, Direction.NORTH, gun);
        GunLinkBlockEntity l2 = gunLink(h, mount.below(), Direction.WEST, gun);
        gunLink(h, mount.south(), Direction.EAST, gun);
        FireControlBlockEntity be = FcsGameTests.fcs(h, fcsPos);
        SettingsGameTests.configure(be, st -> st.dropStatic = false);
        var target = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(28.5, 4, 40.5), 0, 0));
        h.setBlock(new BlockPos(28, 3, 40), Blocks.STONE.defaultBlockState());
        AtomicInteger hits = FcsGameTests.countHits(h, target);
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        float[] yaw0 = {Float.NaN};
        boolean[] sawHold = {false};
        List<String> log = new java.util.ArrayList<>();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            List<MountState> ms = be.mountStates();
            MountState m = ms.isEmpty() ? null : ms.get(0);
            switch (phase.get()) {
                case 0 -> {                                         // автоприцел выключен
                    if (t < 5) return;
                    l1.saveSettings(new dev.radarfcs.fcs.GunSettings(dev.radarfcs.fcs.GunSettings.Type.AUTOCANNON, false, true, "Зенитка-1", 0));
                    if (!l2.settings().name.equals("Зенитка-1") || l2.settings().autoAim) throw new GameTestAssertException("settings not shared: " + l2.settings().write());
                    since.set(t);
                    phase.set(1);
                }
                case 1 -> {
                    if (m == null || m.mount == null || !m.mount.isAssembled()) return;
                    // до сохранения настроек автоприцел был включён: курс - через 20 тиков после выключения
                    if (t - since.get() == 20) yaw0[0] = (float) m.mount.yaw();
                    if (t - since.get() < 80 || Float.isNaN(yaw0[0])) return;
                    if (m.status != Status.GUN_AUTO_OFF || hits.get() > 0 || Math.abs(m.mount.yaw() - yaw0[0]) > 0.5)
                        throw new GameTestAssertException("auto-aim off still engaged: " + m.status + " hits " + hits.get() + " yaw " + yaw0[0] + " -> " + m.mount.yaw());
                    if (!"Зенитка-1".equals(be.summary().getList("Mounts", 10).getCompound(0).getString("G"))) throw new GameTestAssertException("gun name not in summary");
                    log.add("auto-aim off: idle, yaw kept");
                    l2.saveSettings(new dev.radarfcs.fcs.GunSettings(dev.radarfcs.fcs.GunSettings.Type.AUTOCANNON, true, false, "Зенитка-1", 0));
                    since.set(t);
                    phase.set(2);
                }
                case 2 -> {                                         // стрельба выключена - наводится без огня
                    if (m != null && m.status == Status.HOLD) sawHold[0] = true;
                    if (t - since.get() < 100) return;
                    if (hits.get() > 0 || m == null || !sawHold[0] || Math.abs(m.mount.yaw() - yaw0[0]) < 5)
                        throw new GameTestAssertException("fire off: status " + (m == null ? null : m.status) + " hits " + hits.get()
                                + " yaw " + yaw0[0] + " -> " + (m == null ? 0 : m.mount.yaw()) + " pitch " + (m == null ? 0 : m.mount.pitch())
                                + " err " + (m == null ? 0 : m.aimError) + " miss " + (m == null ? 0 : m.predictedMiss) + " tol "
                                + (m == null ? 0 : m.tolerance));
                    log.add("fire off: aimed " + String.format(java.util.Locale.ROOT, "%.1f", m.mount.yaw() - yaw0[0]) + " deg, no shots");
                    l1.saveSettings(new dev.radarfcs.fcs.GunSettings(dev.radarfcs.fcs.GunSettings.Type.AUTOCANNON, true, true, "Зенитка-1", 0));
                    since.set(t);
                    phase.set(3);
                }
                case 3 -> {
                    if (hits.get() >= 2) {
                        log.add("all on: hits " + hits.get() + " after " + (t - since.get()) + " ticks");
                        FcsMod.LOG.info("[radar_fcs test] gun_settings: {}", log);
                        h.succeed();
                    } else if (t - since.get() > 200) {
                        throw new GameTestAssertException("no hits with settings on: " + (m == null ? null : m.status));
                    }
                }
                default -> { }
            }
        });
    }

    private static String describe(List<MountState> ms) {
        StringBuilder sb = new StringBuilder();
        for (MountState m : ms) sb.append(m.pos.toShortString()).append(' ').append(m.status).append(m.turret != null ? " turret" : "")
                .append(m.mount != null ? " mount" : "").append("; ");
        return sb.toString();
    }
}
