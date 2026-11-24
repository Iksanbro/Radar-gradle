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
 * линку на любой его части. Физика подшипников Sable проверяется в dev-клиенте.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class TurretGameTests {
    private static final String ARENA = "arena";

    @BeforeBatch(batch = "turret") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "turret") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "turret_parts") public static void b2(ServerLevel l) { FcsGameTests.direct(l); }
    @BeforeBatch(batch = "turret_bearing") public static void b3(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "turret_bearing") public static void a3(ServerLevel l) { FcsGameTests.cleanup(l); }
    @AfterBatch(batch = "turret_parts") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "turret_bearing_split2") public static void b4(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "turret_bearing_split2") public static void a4(ServerLevel l) { FcsGameTests.cleanup(l); }

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
     * Прицел, привод орудия (без вращения) и автопушка. Радарный линк на прицеле: вычислитель видит орудие (одно
     * состояние на прицеле; прицел уже смотрит на цель - орудие на цели). Станок в орудийной сети - орудие стреляет им (состояние на
     * станке, его углы вычислитель не трогает и валы не блокирует - их могут крутить приводы). Прицел без орудийного
     * линка - орудие без приводов.
     */
    @GameTest(template = ARENA, batch = "turret", timeoutTicks = 400)
    public static void gunTurretGrouping(GameTestHelper h) {
        floor(h);
        BlockPos fcsPos = new BlockPos(6, 1, 28), rot = new BlockPos(10, 1, 30), sight = new BlockPos(12, 1, 30),
                mount = new BlockPos(10, 1, 36);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcsBare(h, fcsPos);
        SettingsGameTests.configure(be, st -> st.dropStatic = false);        // цель стоит - иначе «нет целей»
        h.setBlock(rot, FcsRegistry.GUN_DRIVE.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP)
                .setValue(GunDriveBlock.PITCH, false));
        h.setBlock(sight, FcsRegistry.SIGHT.get().defaultBlockState().setValue(SightBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(rot));
        FcsGameTests.CREATED.add(h.absolutePos(sight));
        UUID gun = UUID.randomUUID();
        gunLink(h, rot, Direction.WEST, gun);
        gunLink(h, sight, Direction.UP, gun);
        link(h, sight, Direction.NORTH, fcsPos);
        SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(30.5, 1, 30.5), 0, 0));
        BlockPos sAbs = h.absolutePos(sight), mAbs = h.absolutePos(mount), rAbs = h.absolutePos(rot);
        if (LinkKind.at(h.getLevel(), sAbs) != LinkKind.SIGHT || LinkKind.at(h.getLevel(), rAbs) != LinkKind.GUN_DRIVE) {
            throw new GameTestAssertException("sight/drive are not link equipment");
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
                    if (!group.contains(rAbs)) throw new GameTestAssertException("gun group without drive: " + group);
                    if (ms.size() == 1 && ms.get(0).pos.equals(sAbs) && ms.get(0).turret != null && ms.get(0).mount == null
                            && ms.get(0).turret.drives.contains(rAbs)) {
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
                    if (m != null && m.pos.equals(mAbs) && m.turret != null && m.mount != null && m.mount.isAssembled()) {
                        if (Float.isNaN(yaw0[0])) {
                            yaw0[0] = (float) m.mount.yaw();
                            since.set(t);
                        } else if (t - since.get() >= 40) {
                            // цель есть, но станок орудия не поворачивается сам - наводят приводы
                            if (Math.abs(m.mount.yaw() - yaw0[0]) > 0.01) {
                                throw new GameTestAssertException("turret mount was turned: " + yaw0[0] + " -> " + m.mount.yaw());
                            }
                            // валы станка орудия не блокируются: их могут крутить приводы (иначе орудие не наводится)
                            if (m.locked) throw new GameTestAssertException("turret mount shafts are locked");
                            h.setBlock(sight.above(), Blocks.AIR.defaultBlockState());   // прицел из орудия
                            be.requestDiscover();
                            since.set(t);
                            phase.set(2);
                        }
                    } else if (t - since.get() > 150) {
                        throw new GameTestAssertException("mount not part of the turret: " + describe(ms));
                    }
                }
                case 2 -> {                                         // прицел сам по себе: без приводов
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

    /**
     * Основная пушка (Shift+ПКМ по орудийному линку на станке): в орудии два станка без прицела, приводы крутят только
     * второй. Он отмечен основным - вычислитель наводит по его стволу (ключ и ведущий орудия) и попадает.
     */
    @GameTest(template = ARENA, batch = "turret", timeoutTicks = 500)
    public static void gunMainMountIsAimReference(GameTestHelper h) {
        floor(h);
        BlockPos other = new BlockPos(10, 1, 24), mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28);
        autocannon(h, other, Direction.EAST, 4, 64);
        autocannon(h, mount, Direction.EAST, 4, 64);
        h.setBlock(mount.below(), FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(DirectionalKineticBlock.FACING, Direction.UP).setValue(GunDriveBlock.PITCH, false));
        h.setBlock(mount.south(), FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(DirectionalKineticBlock.FACING, Direction.NORTH).setValue(GunDriveBlock.PITCH, true));
        UUID gun = UUID.randomUUID();
        gunLink(h, other, Direction.NORTH, gun);
        GunLinkBlockEntity onMain = gunLink(h, mount, Direction.NORTH, gun);
        gunLink(h, mount.below(), Direction.WEST, gun);
        gunLink(h, mount.south(), Direction.EAST, gun);
        FireControlBlockEntity be = FcsGameTests.fcs(h, fcsPos);
        SettingsGameTests.configure(be, st -> st.dropStatic = false);
        onMain.saveSettings(new dev.radarfcs.fcs.GunSettings(dev.radarfcs.fcs.GunSettings.Type.AUTOCANNON, true, true, "", 0, h.absolutePos(mount)));
        be.requestDiscover();
        var target = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(28.5, 4, 46.5), 0, 0));
        h.setBlock(new BlockPos(28, 3, 46), Blocks.STONE.defaultBlockState());
        AtomicInteger hits = FcsGameTests.countHits(h, target);
        BlockPos mAbs = h.absolutePos(mount);
        h.onEachTick(() -> {
            MountState lead = null;
            for (MountState m : be.mountStates()) if (m.turret != null && m.pos.equals(m.turret.leader)) lead = m;
            if (lead == null) return;
            if (!lead.pos.equals(mAbs) || !lead.turret.key.equals(mAbs))
                throw new GameTestAssertException("main gun is not the leader: leader " + lead.pos + " key " + lead.turret.key);
            if (hits.get() >= 2) {
                FcsMod.LOG.info("[radar_fcs test] gun_main: main gun leads, hits {} at t={}", hits.get(), h.getTick());
                h.succeed();
            } else if (h.getTick() > 460) {
                throw new GameTestAssertException("main gun did not hit: " + lead.status + " err " + lead.aimError);
            }
        });
    }

    /**
     * Своя поворотная часть сборного орудия - не цель (как в 1.0): прицел на постройке Sable (поворотная часть
     * подшипника), рядом - вторая постройка без блоков мода (платформа между подшипниками). Радар видит обе как
     * корабли; с 1.1-Test корабли - свой-чужой только по транспондеру и неподвижные не сбрасываются, и орудие наводилось
     * на собственную платформу. Орудие должно вести хаска и ни разу не взять свою постройку.
     */
    @GameTest(template = ARENA, batch = "turret_parts", timeoutTicks = 300)
    public static void gunNeverTargetsItsOwnTurretParts(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        BlockPos fcsPos = new BlockPos(6, 1, 28), drive = new BlockPos(10, 1, 30), sight = new BlockPos(12, 2, 30);
        FireControlBlockEntity be = fcsBare(h, fcsPos);
        SettingsGameTests.configure(be, st -> st.dropStatic = false);
        h.setBlock(drive, FcsRegistry.GUN_DRIVE.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP)
                .setValue(GunDriveBlock.PITCH, false));
        FcsGameTests.CREATED.add(h.absolutePos(drive));
        h.setBlock(sight.below(), Blocks.IRON_BLOCK.defaultBlockState());
        h.setBlock(sight, FcsRegistry.SIGHT.get().defaultBlockState().setValue(SightBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(sight));
        UUID gun = UUID.randomUUID();
        gunLink(h, sight, Direction.UP, gun);
        gunLink(h, drive, Direction.EAST, gun);
        link(h, drive, Direction.WEST, fcsPos);
        for (int x = 13; x <= 14; x++) for (int y = 1; y <= 2; y++) h.setBlock(new BlockPos(x, y, 30), Blocks.IRON_BLOCK.defaultBlockState());
        Object part = SableTestSupport.staticSub(h, sight.below(), sight.above());        // поворотная часть с прицелом
        Object platform = SableTestSupport.staticSub(h, new BlockPos(13, 1, 30), new BlockPos(14, 2, 30));   // без блоков мода
        UUID partId = SableTestSupport.subId(part), platformId = SableTestSupport.subId(platform);
        var husk = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(34.5, 1, 40.5), 0, 0));
        AtomicInteger huskTicks = new AtomicInteger();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            if (t < 30) return;
            for (MountState m : be.mountStates()) {
                if (m.targetId == null) continue;
                if (m.targetId.equals(partId.toString()) || m.targetId.equals(platformId.toString())) {
                    throw new GameTestAssertException("gun targets its own turret part " + m.targetId + " (" + m.targetName + ")");
                }
                if (m.targetId.equals(husk.getUUID().toString())) huskTicks.incrementAndGet();
            }
            if (t >= 200) {
                if (huskTicks.get() < 20) throw new GameTestAssertException("gun did not engage the husk: " + describe(be.mountStates()));
                h.succeed();
            }
        });
    }

    /**
     * Вал насквозь подшипника орудия: мотор под подшипником, над ним вал (поворотная часть). После сборки вал в плоте
     * поворотной части крутится от мотора через подшипник, а сама поворотная часть стоит (шестерни рядом нет).
     */
    @GameTest(template = ARENA, batch = "turret_bearing_shaft", timeoutTicks = 200)
    public static void gunBearingShaftPassesThrough(GameTestHelper h) {
        if (!dev.radarfcs.compat.Mods.SIMULATED) {
            h.succeed();
            return;
        }
        floor(h);
        BlockPos bearing = new BlockPos(20, 2, 20);
        FcsGameTests.motor(h, bearing.below(), Direction.UP, 64);
        h.setBlock(bearing, dev.radarfcs.block.GunBearings.BLOCK.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP));
        h.setBlock(bearing.above(), FcsGameTests.block("create:shaft").defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Y));
        h.setBlock(bearing.above(2), FcsGameTests.block("create:andesite_casing").defaultBlockState());
        FcsGameTests.CREATED.add(h.absolutePos(bearing));
        BlockPos a = h.absolutePos(bearing.above());
        h.getLevel().addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(h.getLevel(),
                new net.minecraft.world.phys.AABB(a.getX(), a.getY(), a.getZ(), a.getX() + 1, a.getY() + 2, a.getZ() + 1)));
        net.minecraft.world.entity.player.Player pl = h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
        pl.setShiftKeyDown(true);
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            if (!(h.getLevel().getBlockEntity(h.absolutePos(bearing)) instanceof dev.radarfcs.block.GunBearingBlockEntity g)) {
                throw new GameTestAssertException("no gun bearing");
            }
            if (t == 5) {
                g.assembleNow();
                SableTestSupport.trackId(h.getLevel(), g.subLevel());
                if (!g.assembled() || g.plotFront() == null) throw new GameTestAssertException("bearing did not assemble");
                return;
            }
            if (t < 40) return;
            float in = g.getSpeed();
            float out = h.getLevel().getBlockEntity(g.plotFront()) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity k ? k.getSpeed() : 0;
            FcsMod.LOG.info("[radar_fcs test] bearing passthrough t {} bearing {} rpm, shaft on the turning part {} rpm, angle {}", t, in, out,
                    String.format(java.util.Locale.ROOT, "%.2f", g.angle()));
            if (Math.abs(in) < 1 || Math.abs(out) < 1) {
                if (t > 120) throw new GameTestAssertException("shaft does not pass through: bearing " + in + " rpm, turning part " + out + " rpm");
                return;
            }
            if (Math.abs(g.angle()) > 0.5) throw new GameTestAssertException("turning part turned without a cogwheel: " + g.angle());
            h.succeed();
        });
    }

    /**
     * Физичная сборка подшипника орудия: шестерня крутит поворотную часть (площадка и рука из камня на 3 блока), на пути
     * руки с обеих сторон - камень. Рука упирается и стоит (шестерня проскальзывает), в камень не входит; камень убрали -
     * поворот продолжается.
     */
    @GameTest(template = ARENA, batch = "turret_bearing_stop", timeoutTicks = 300)
    public static void gunBearingStopsAtBlocks(GameTestHelper h) {
        if (!dev.radarfcs.compat.Mods.SIMULATED) {
            h.succeed();
            return;
        }
        floor(h);
        BlockPos bearing = new BlockPos(20, 2, 20), cog = bearing.east();
        FcsGameTests.motor(h, cog.below(), Direction.UP, 16);
        h.setBlock(cog, FcsGameTests.block("create:cogwheel").defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Y));
        h.setBlock(bearing, dev.radarfcs.block.GunBearings.BLOCK.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP));
        h.setBlock(bearing.above(), FcsGameTests.block("create:andesite_casing").defaultBlockState());
        for (int i = 1; i <= 3; i++) h.setBlock(bearing.above().east(i), Blocks.STONE.defaultBlockState());
        List<BlockPos> stops = List.of(bearing.above().east(2).south(2), bearing.above().east(2).north(2));
        FcsGameTests.CREATED.add(h.absolutePos(bearing));
        BlockPos a = h.absolutePos(bearing.above());
        h.getLevel().addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(h.getLevel(),
                new net.minecraft.world.phys.AABB(a.getX(), a.getY(), a.getZ(), a.getX() + 4, a.getY() + 1, a.getZ() + 1)));
        net.minecraft.world.entity.player.Player pl = h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
        pl.setShiftKeyDown(true);
        double[] held = {Double.NaN};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            if (!(h.getLevel().getBlockEntity(h.absolutePos(bearing)) instanceof dev.radarfcs.block.GunBearingBlockEntity g)) {
                throw new GameTestAssertException("no gun bearing");
            }
            if (t == 5) {
                g.assembleNow();
                SableTestSupport.trackId(h.getLevel(), g.subLevel());
                if (!g.assembled()) throw new GameTestAssertException("bearing did not assemble");
                return;
            }
            if (t == 7) {
                for (var e : h.getLevel().getEntitiesOfClass(com.simibubi.create.content.contraptions.glue.SuperGlueEntity.class,
                        new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(64))) e.discard();
                // камни ставятся после сборки (иначе приклеились бы к руке)
                for (BlockPos p : stops) h.setBlock(p, Blocks.STONE.defaultBlockState());
                return;
            }
            if (t == 100) held[0] = g.angle();
            if (t == 140) {
                double ang = g.angle();
                FcsMod.LOG.info("[radar_fcs test] bearing stop: ring {} rpm, angle {} -> {}", g.ringSpeed(),
                        String.format(java.util.Locale.ROOT, "%.2f", held[0]), String.format(java.util.Locale.ROOT, "%.2f", ang));
                if (Math.abs(g.ringSpeed()) < 1) throw new GameTestAssertException("cogwheel does not turn the bearing");
                if (Math.abs(ang) < 10 || Math.abs(ang) > 70) throw new GameTestAssertException("arm did not stop at the stone: " + ang);
                if (Math.abs(ang - held[0]) > 0.5) throw new GameTestAssertException("blocked arm still turns: " + held[0] + " -> " + ang);
                for (BlockPos p : stops) h.setBlock(p, Blocks.AIR.defaultBlockState());
                return;
            }
            if (t == 200) {
                double ang = g.angle();
                FcsMod.LOG.info("[radar_fcs test] bearing stop: stones removed, angle {}", String.format(java.util.Locale.ROOT, "%.2f", ang));
                if (Math.abs(dev.radarfcs.core.Aim.wrap180(ang - held[0])) < 20) throw new GameTestAssertException("arm did not turn on after the stones were removed: " + ang);
                h.succeed();
            }
        });
    }

    /**
     * Подшипники орудия с приводами (как подшипники Aeronautics в постройке пользователя): мотор -> привод курса ->
     * шестерня рядом с подшипником курса (выход вверх); на его площадке мотор -> привод наклона -> шестерня рядом с
     * подшипником наклона (выход на север);
     * на качающейся части - ствол из 8 блоков камня и прицел. Цель сбоку и выше: вычислитель доводит прицел приводами и
     * держит без раскачки (размах прицела за 60 тиков < 0.5°, ошибка < 2°).
     */
    @GameTest(template = ARENA, batch = "turret_bearing", timeoutTicks = 760)
    public static void gunBearingsAimWithoutWobble(GameTestHelper h) {
        if (!dev.radarfcs.compat.Mods.SIMULATED) {
            h.succeed();
            return;
        }
        floor(h);
        BlockPos fcsPos = new BlockPos(6, 1, 26), yaw = new BlockPos(10, 3, 30), yawCog = yaw.east(), yawDrive = yawCog.below(),
                pitch = new BlockPos(10, 6, 30), pitchCog = pitch.east(), pitchDrive = pitchCog.south(), sight = new BlockPos(11, 7, 29);
        FcsGameTests.motor(h, yawDrive.below(), Direction.UP, 64);
        h.setBlock(yawDrive, FcsRegistry.GUN_DRIVE.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP)
                .setValue(GunDriveBlock.PITCH, false));
        h.setBlock(yawCog, FcsGameTests.block("create:cogwheel").defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Y));
        h.setBlock(yaw, dev.radarfcs.block.GunBearings.BLOCK.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP));
        var casing = FcsGameTests.block("create:andesite_casing").defaultBlockState();
        h.setBlock(yaw.above(), casing);
        h.setBlock(yaw.above(2), casing);
        h.setBlock(pitch, dev.radarfcs.block.GunBearings.BLOCK.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.NORTH));
        h.setBlock(pitchCog, FcsGameTests.block("create:cogwheel").defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Z));
        h.setBlock(pitchDrive, FcsRegistry.GUN_DRIVE.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.NORTH)
                .setValue(GunDriveBlock.PITCH, true));
        FcsGameTests.motor(h, pitchDrive.south(), Direction.NORTH, 64);
        for (int i = 0; i < 8; i++) h.setBlock(pitch.north().east(i), Blocks.STONE.defaultBlockState());
        h.setBlock(sight, FcsRegistry.SIGHT.get().defaultBlockState().setValue(SightBlock.FACING, Direction.EAST));
        for (BlockPos p : List.of(yawDrive, yaw, pitch, pitchDrive, sight)) FcsGameTests.CREATED.add(h.absolutePos(p));
        UUID gun = UUID.randomUUID();
        gunLink(h, sight, Direction.UP, gun);
        gunLink(h, yawDrive, Direction.EAST, gun);
        gunLink(h, pitchDrive, Direction.UP, gun);
        FireControlBlockEntity be = fcsBare(h, fcsPos);
        SettingsGameTests.configure(be, st -> st.dropStatic = false);
        link(h, yawDrive, Direction.SOUTH, fcsPos);
        // клей: площадка с подшипником наклона, его шестернёй, приводом и мотором; ствол с прицелом и линком
        BlockPos a = h.absolutePos(yaw.above()), b = h.absolutePos(pitch.north());
        h.getLevel().addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(h.getLevel(),
                new net.minecraft.world.phys.AABB(a.getX(), a.getY(), a.getZ(), a.getX() + 2, a.getY() + 4, a.getZ() + 3)));
        h.getLevel().addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(h.getLevel(),
                new net.minecraft.world.phys.AABB(b.getX(), b.getY(), b.getZ(), b.getX() + 8, b.getY() + 3, b.getZ() + 1)));
        h.setBlock(new BlockPos(30, 8, 40), Blocks.STONE.defaultBlockState());
        var husk = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(30.5, 9, 40.5), 0, 0));
        net.minecraft.world.entity.player.Player pl = h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
        pl.setShiftKeyDown(true);
        List<Vec3> dirs = new java.util.ArrayList<>();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            if (t == 5) {
                // сначала наклон (ствол - его поворотная часть), затем курс (площадка с подшипником и приводом наклона)
                for (BlockPos r : List.of(pitch, yaw)) {
                    if (h.getLevel().getBlockEntity(h.absolutePos(r)) instanceof dev.radarfcs.block.GunBearingBlockEntity g) {
                        g.assembleNow();
                        SableTestSupport.trackId(h.getLevel(), g.subLevel());          // убрать после пакета
                    } else throw new GameTestAssertException("no gun bearing at " + r);
                }
                be.requestDiscover();
                return;
            }
            if (t == 6) {
                for (var e : h.getLevel().getEntitiesOfClass(com.simibubi.create.content.contraptions.glue.SuperGlueEntity.class,
                        new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(64))) e.discard();
                return;
            }
            if (t < 20) return;
            MountState m = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            SightBlockEntity se = m == null || m.turret == null ? null : m.turret.sightEntity(h.getLevel());
            if (se == null) {
                if (t > 300) throw new GameTestAssertException("turret with sight not found: " + describe(be.mountStates()));
                return;
            }
            if (t % 40 == 0) {
                StringBuilder sb = new StringBuilder();
                for (var dr : m.turret.driveEntities(h.getLevel())) sb.append(String.format(java.util.Locale.ROOT, " [%s out %.3f rate %.3f]",
                        dr.pitch() ? "P" : "Y", dr.output(), dr.rate()));
                for (var gb : dev.radarfcs.block.GunBearingBlockEntity.active()) sb.append(String.format(java.util.Locale.ROOT, " {%s ring %.2f t %.2f}",
                        gb.facing(), gb.ringSpeed(), gb.target()));
                FcsMod.LOG.info("[radar_fcs test] gun_bearings t {} status {} target {} err {}{}", t, m.status, m.targetName,
                        String.format(java.util.Locale.ROOT, "%.2f", m.aimError), sb);
            }
            if (t < 640) return;
            dirs.add(se.worldDirection());
            if (dirs.size() < 60) return;
            Vec3 mean = Vec3.ZERO;
            for (Vec3 d : dirs) mean = mean.add(d);
            mean = mean.normalize();
            double wobble = 0;
            for (Vec3 d : dirs) wobble = Math.max(wobble, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, d.dot(mean))))));
            FcsMod.LOG.info("[radar_fcs test] gun_bearings: status {} target {} err {} wobble {} deg", m.status, m.targetName,
                    String.format(java.util.Locale.ROOT, "%.2f", m.aimError), String.format(java.util.Locale.ROOT, "%.3f", wobble));
            if (!husk.getUUID().toString().equals(m.targetId)) throw new GameTestAssertException("gun is not on the husk: " + m.targetName);
            if (wobble > 0.5) throw new GameTestAssertException("turret sways: " + String.format(java.util.Locale.ROOT, "%.2f", wobble)
                    + " deg, err " + m.aimError + ", status " + m.status);
            if (!(m.aimError < 2)) throw new GameTestAssertException("turret not on target: err " + m.aimError + ", status " + m.status);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "turret_bearing_split2", timeoutTicks = 900)
    public static void gunTurretDisassemblesWhileAiming(GameTestHelper h) {
        if (!dev.radarfcs.compat.Mods.SIMULATED) {
            h.succeed();
            return;
        }
        floor(h);
        BlockPos fcsPos = new BlockPos(6, 1, 26), yaw = new BlockPos(10, 3, 30), yawCog = yaw.east(), yawDrive = yawCog.below(),
                pitch = new BlockPos(10, 6, 30), pitchCog = pitch.east(), pitchDrive = pitchCog.south(), sight = new BlockPos(11, 7, 29);
        FcsGameTests.motor(h, yawDrive.below(), Direction.UP, 64);
        h.setBlock(yawDrive, FcsRegistry.GUN_DRIVE.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP)
                .setValue(GunDriveBlock.PITCH, false));
        h.setBlock(yawCog, FcsGameTests.block("create:cogwheel").defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Y));
        h.setBlock(yaw, dev.radarfcs.block.GunBearings.BLOCK.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP));
        var casing = FcsGameTests.block("create:andesite_casing").defaultBlockState();
        h.setBlock(yaw.above(), casing);
        h.setBlock(yaw.above(2), casing);
        h.setBlock(pitch, dev.radarfcs.block.GunBearings.BLOCK.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.NORTH));
        h.setBlock(pitchCog, FcsGameTests.block("create:cogwheel").defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Z));
        h.setBlock(pitchDrive, FcsRegistry.GUN_DRIVE.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.NORTH)
                .setValue(GunDriveBlock.PITCH, true));
        FcsGameTests.motor(h, pitchDrive.south(), Direction.NORTH, 64);
        for (int i = 0; i < 8; i++) h.setBlock(pitch.north().east(i), Blocks.STONE.defaultBlockState());
        h.setBlock(sight, FcsRegistry.SIGHT.get().defaultBlockState().setValue(SightBlock.FACING, Direction.EAST));
        for (BlockPos p : List.of(yawDrive, yaw, pitch, pitchDrive, sight)) FcsGameTests.CREATED.add(h.absolutePos(p));
        UUID gun = UUID.randomUUID();
        gunLink(h, sight, Direction.UP, gun);
        gunLink(h, yawDrive, Direction.EAST, gun);
        gunLink(h, pitchDrive, Direction.UP, gun);
        FireControlBlockEntity be = fcsBare(h, fcsPos);
        SettingsGameTests.configure(be, st -> st.dropStatic = false);
        link(h, yawDrive, Direction.SOUTH, fcsPos);
        // клей: площадка с подшипником наклона, его шестернёй, приводом и мотором; ствол с прицелом и линком
        BlockPos a = h.absolutePos(yaw.above()), b = h.absolutePos(pitch.north());
        h.getLevel().addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(h.getLevel(),
                new net.minecraft.world.phys.AABB(a.getX(), a.getY(), a.getZ(), a.getX() + 2, a.getY() + 4, a.getZ() + 3)));
        h.getLevel().addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(h.getLevel(),
                new net.minecraft.world.phys.AABB(b.getX(), b.getY(), b.getZ(), b.getX() + 8, b.getY() + 3, b.getZ() + 1)));
        h.setBlock(new BlockPos(30, 8, 40), Blocks.STONE.defaultBlockState());
        var husk = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(30.5, 9, 40.5), 0, 0));
        net.minecraft.world.entity.player.Player pl = h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
        pl.setShiftKeyDown(true);
        int[] split = {-1, -1};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            var level = h.getLevel();
            if (t == 5) {
                for (BlockPos r : List.of(pitch, yaw)) {
                    if (level.getBlockEntity(h.absolutePos(r)) instanceof dev.radarfcs.block.GunBearingBlockEntity g) {
                        g.assembleNow();
                        SableTestSupport.trackId(level, g.subLevel());
                    } else throw new GameTestAssertException("no gun bearing at " + r);
                }
                be.requestDiscover();
                return;
            }
            if (t < 7) return;
            if (!(level.getBlockEntity(h.absolutePos(yaw)) instanceof dev.radarfcs.block.GunBearingBlockEntity g)) {
                throw new GameTestAssertException("no yaw bearing");
            }
            MountState ms = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            if (split[0] < 0) {                                                 // наводка идёт - разобрать курс (ПКМ)
                if (t < 40 || Math.abs(g.ringSpeed()) < 0.1 && Math.abs(g.angle()) < 8) {
                    if (t > 400) throw new GameTestAssertException("turret does not turn");
                    return;
                }
                FcsMod.LOG.info("[radar_fcs test] turret split: disassembling yaw at angle {} ring {}",
                        String.format(java.util.Locale.ROOT, "%.1f", g.angle()), g.ringSpeed());
                g.assembleNextTick = true;
                split[0] = t;
                return;
            }
            int dt = t - split[0];
            if (split[1] < 0) {
                if (dt < 40) {
                    if (g.assembled()) throw new GameTestAssertException("yaw bearing re-assembled by itself " + dt + " ticks after a manual disassembly");
                    return;
                }
                // разобрано: блоки курса в мире на своих местах, ствол на подшипнике наклона (он теперь в мире)
                for (BlockPos r : List.of(yaw.above(), yaw.above(2), pitchCog, pitchDrive)) {
                    if (level.getBlockState(h.absolutePos(r)).isAir()) throw new GameTestAssertException("block lost on disassembly at " + r);
                }
                if (!(level.getBlockEntity(h.absolutePos(pitch)) instanceof dev.radarfcs.block.GunBearingBlockEntity pg) || !pg.assembled()
                        || pg.plotFront() == null) throw new GameTestAssertException("pitch bearing lost on disassembly");
                Vec3 w = dev.radarfcs.compat.SableCompat.toWorld(level, Vec3.atCenterOf(pg.plotFront()));
                double off = w.distanceTo(Vec3.atCenterOf(h.absolutePos(pitch.north())));
                FcsMod.LOG.info("[radar_fcs test] turret split: disassembled, ring {} barrel off {}", g.ringSpeed(),
                        String.format(java.util.Locale.ROOT, "%.2f", off));
                if (off > 3) throw new GameTestAssertException("barrel not on its bearing after disassembly: " + off);
                g.assembleNextTick = true;                                      // снова ПКМ - собрать
                split[1] = t;
                return;
            }
            int dr = t - split[1];
            if (dr == 1 && !g.assembled()) throw new GameTestAssertException("yaw bearing did not re-assemble on right-click");
            if (dr % 20 == 0 && ms != null) FcsMod.LOG.info("[radar_fcs test] turret split: re-assembled +{} status {} err {} angle {}", dr, ms.status,
                    String.format(java.util.Locale.ROOT, "%.2f", ms.aimError), String.format(java.util.Locale.ROOT, "%.1f", g.angle()));
            if (ms != null && ms.aimError < 2 && dr > 20) {
                h.succeed();
                return;
            }
            if (dr > 300) throw new GameTestAssertException("turret did not aim again after re-assembly: err " + (ms == null ? "-" : ms.aimError));
        });
    }

    /**
     * Разборка подшипника орудия на ходу: шестерня крутит поворотную часть (рука из камня на 3 блока, на её конце -
     * подшипник второй поворотной части со своим камнем). На повороте ~40° подшипник разбирают (ПКМ): блоки руки
     * возвращаются в мир на свои места, вторая поворотная часть остаётся на своём подшипнике, ничего не пропадает.
     */
    @GameTest(template = ARENA, batch = "turret_bearing_split", timeoutTicks = 300)
    public static void gunBearingDisassemblesWhileTurning(GameTestHelper h) {
        if (!dev.radarfcs.compat.Mods.SIMULATED) {
            h.succeed();
            return;
        }
        floor(h);
        BlockPos bearing = new BlockPos(20, 2, 20), cog = bearing.east(), arm = bearing.above(), sub = arm.east(3).above();
        h.setBlock(cog, FcsGameTests.block("create:cogwheel").defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Y));
        h.setBlock(bearing, dev.radarfcs.block.GunBearings.BLOCK.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP));
        h.setBlock(arm, FcsGameTests.block("create:andesite_casing").defaultBlockState());
        for (int i = 1; i <= 3; i++) h.setBlock(arm.east(i), Blocks.STONE.defaultBlockState());
        h.setBlock(sub, dev.radarfcs.block.GunBearings.BLOCK.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP));
        h.setBlock(sub.above(), Blocks.GOLD_BLOCK.defaultBlockState());
        FcsGameTests.CREATED.add(h.absolutePos(bearing));
        FcsGameTests.CREATED.add(h.absolutePos(sub));
        BlockPos a = h.absolutePos(arm);
        h.getLevel().addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(h.getLevel(),
                new net.minecraft.world.phys.AABB(a.getX(), a.getY(), a.getZ(), a.getX() + 4, a.getY() + 2, a.getZ() + 1)));
        net.minecraft.world.entity.player.Player pl = h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
        pl.setShiftKeyDown(true);
        int[] split = {-1};
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            var level = h.getLevel();
            // сначала вторая поворотная часть, потом мотор (закрутившееся кольцо собирает первый подшипник само)
            if (t == 1 && level.getBlockEntity(h.absolutePos(sub)) instanceof dev.radarfcs.block.GunBearingBlockEntity g2) {
                g2.assembleNow();
                if (!g2.assembled()) throw new GameTestAssertException("second bearing did not assemble");
                SableTestSupport.trackId(level, g2.subLevel());
                return;
            }
            if (t == 2) {
                FcsGameTests.motor(h, cog.below(), Direction.UP, 16);
                return;
            }
            if (t == 5) {
                for (BlockPos r : List.of(bearing)) {
                    if (level.getBlockEntity(h.absolutePos(r)) instanceof dev.radarfcs.block.GunBearingBlockEntity g) {
                        g.assembleNow();
                        if (!g.assembled()) throw new GameTestAssertException("bearing did not assemble at " + r);
                        SableTestSupport.trackId(level, g.subLevel());
                    } else throw new GameTestAssertException("no gun bearing at " + r + ": " + level.getBlockState(h.absolutePos(r))
                            + " / " + level.getBlockEntity(h.absolutePos(r)));
                }
                return;
            }
            if (t == 6) {
                for (var e : level.getEntitiesOfClass(com.simibubi.create.content.contraptions.glue.SuperGlueEntity.class,
                        new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(64))) e.discard();
                return;
            }
            if (t < 7) return;
            if (split[0] < 0) {
                if (!(level.getBlockEntity(h.absolutePos(bearing)) instanceof dev.radarfcs.block.GunBearingBlockEntity g)) {
                    throw new GameTestAssertException("no gun bearing");
                }
                if (Math.abs(g.angle()) < 40) {
                    if (t > 200) throw new GameTestAssertException("bearing does not turn: " + g.angle());
                    return;
                }
                FcsMod.LOG.info("[radar_fcs test] bearing split: disassembling at angle {}", String.format(java.util.Locale.ROOT, "%.1f", g.angle()));
                g.assembleNextTick = true;                                     // ПКМ пустой рукой
                split[0] = t;
                return;
            }
            if (t - split[0] < 20) return;
            StringBuilder sb = new StringBuilder();
            boolean ok = true;
            for (int i = 0; i <= 3; i++) {
                net.minecraft.world.level.block.state.BlockState st = level.getBlockState(h.absolutePos(arm.east(i)));
                sb.append(' ').append(i).append('=').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath());
                if (st.isAir()) ok = false;
            }
            net.minecraft.world.level.block.state.BlockState subState = level.getBlockState(h.absolutePos(sub));
            sb.append(" sub=").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(subState.getBlock()).getPath());
            String subInfo = "-";
            if (level.getBlockEntity(h.absolutePos(sub)) instanceof dev.radarfcs.block.GunBearingBlockEntity sg && sg.assembled()
                    && sg.plotFront() != null) {
                Vec3 w = dev.radarfcs.compat.SableCompat.toWorld(level, Vec3.atCenterOf(sg.plotFront()));
                Vec3 want = Vec3.atCenterOf(h.absolutePos(sub.above()));
                subInfo = String.format(java.util.Locale.ROOT, "gold at %.2f %.2f %.2f, off %.2f", w.x, w.y, w.z, w.distanceTo(want));
                if (w.distanceTo(want) > 0.6) ok = false;
            } else ok = false;
            if (level.getBlockEntity(h.absolutePos(bearing)) instanceof dev.radarfcs.block.GunBearingBlockEntity g && g.assembled()) {
                sb.append(" yaw still assembled");
                ok = false;
            }
            FcsMod.LOG.info("[radar_fcs test] bearing split: {} ticks after:{} | second part {}", t - split[0], sb, subInfo);
            if (ok) {
                h.succeed();
                return;
            }
            if (t - split[0] > 60) throw new GameTestAssertException("turning part lost on disassembly:" + sb + " | second part " + subInfo);
        });
    }

    private static String describe(List<MountState> ms) {
        StringBuilder sb = new StringBuilder();
        for (MountState m : ms) sb.append(m.pos.toShortString()).append(' ').append(m.status).append(m.turret != null ? " turret" : "")
                .append(m.mount != null ? " mount" : "").append("; ");
        return sb.toString();
    }
}
