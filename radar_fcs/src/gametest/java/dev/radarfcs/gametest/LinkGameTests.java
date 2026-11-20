package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.bigCannon;
import static dev.radarfcs.gametest.FcsGameTests.block;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.fcsBare;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.link;
import static dev.radarfcs.gametest.FcsGameTests.linkMount;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.block.RadarMonitorBlock;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.item.RadarLinkItem;
import dev.radarfcs.link.LinkRegistry;
import dev.radarfcs.registry.FcsRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Радарный линк: подключение станка предметом, монитор с радаром Create Radars и назначением цели своим вычислителям. */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class LinkGameTests {

    private static final String ARENA = "arena";

    @BeforeBatch(batch = "link_mount") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "link_mount") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "link_crmonitor") public static void b2(ServerLevel l) { FcsGameTests.beforeRadar(l); }
    @AfterBatch(batch = "link_crmonitor") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "link_monitor") public static void b3(ServerLevel l) { FcsGameTests.beforeRadar(l); }
    @AfterBatch(batch = "link_monitor") public static void a3(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "link_monitor_fire") public static void b4(ServerLevel l) { FcsGameTests.beforeRadar(l); }
    @AfterBatch(batch = "link_monitor_fire") public static void a4(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "link_monitor_exclude") public static void b5(ServerLevel l) { FcsGameTests.beforeRadar(l); }
    @AfterBatch(batch = "link_monitor_exclude") public static void a5(ServerLevel l) { FcsGameTests.cleanup(l); }
    @AfterBatch(batch = "monitor_pins") public static void a6(ServerLevel l) { FcsGameTests.cleanup(l); }
    @AfterBatch(batch = "link_sticky") public static void a7(ServerLevel l) { FcsGameTests.cleanup(l); }
    @AfterBatch(batch = "link_limit") public static void a8(ServerLevel l) { FcsGameTests.cleanup(l); }

    /** ПКМ предметом в руке игрока по грани блока (как в игре). */
    static InteractionResult use(GameTestHelper h, Player p, BlockPos rel, Direction face) {
        BlockPos abs = h.absolutePos(rel);
        Vec3 at = Vec3.atCenterOf(abs).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        return p.getMainHandItem().getItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(at, face, abs, false)));
    }

    /**
     * Сеть линков, как у складских передатчиков Create: ненастроенный линк на вычислителе начинает новую сеть, ПКМ по нему
     * настраивает предмет, линк на станке в 29 блоках встаёт в ту же сеть - вычислитель управляет только этим станком.
     * Станок рядом без линка не управляется никем (автоподключения нет), второй вычислитель без линков пушек не имеет.
     * Shift+ПКМ в воздух сбрасывает настройку предмета.
     */
    @GameTest(template = ARENA, batch = "link_mount", timeoutTicks = 500)
    public static void linkedByItem(GameTestHelper h) {
        floor(h);
        BlockPos farMount = new BlockPos(10, 1, 32), nearMount = new BlockPos(10, 1, 8), fcsPos = new BlockPos(4, 1, 4);
        autocannon(h, farMount, Direction.EAST, 4, 64);
        autocannon(h, nearMount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcsBare(h, fcsPos);
        FireControlBlockEntity other = fcsBare(h, new BlockPos(6, 1, 36));       // рядом с дальним станком, без линков

        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FcsRegistry.RADAR_LINK_ITEM.get(), 4));
        // 1. ненастроенный линк на вычислитель - новая сеть
        if (!use(h, p, fcsPos, Direction.UP).consumesAction()) h.fail("placing the first link failed");
        BlockPos fcsLink = fcsPos.above();
        FcsGameTests.CREATED.add(h.absolutePos(fcsLink));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(fcsLink)) instanceof RadarLinkBlockEntity first) || first.network() == null) {
            h.fail("first link has no network");
            return;
        }
        UUID net = first.network();
        if (RadarLinkItem.network(p.getMainHandItem()) != null) h.fail("placing an untuned link must not tune the item");
        // 2. ПКМ по линку - предмет настроен на его сеть
        if (!use(h, p, fcsLink, Direction.UP).consumesAction()) h.fail("tuning on the link failed");
        if (!net.equals(RadarLinkItem.network(p.getMainHandItem()))) h.fail("item not tuned to the network");
        // 3. линк на дальний станок - в ту же сеть
        if (!use(h, p, farMount, Direction.WEST).consumesAction()) h.fail("placing the link on the mount failed");
        BlockPos linkAt = farMount.relative(Direction.WEST);
        FcsGameTests.CREATED.add(h.absolutePos(linkAt));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(linkAt)) instanceof RadarLinkBlockEntity second) || !net.equals(second.network())) {
            h.fail("mount link is not in the network");
            return;
        }
        if (!LinkRegistry.linked(h.getLevel(), h.absolutePos(fcsPos), h.absolutePos(farMount))) h.fail("link not registered");
        if (LinkRegistry.linked(h.getLevel(), h.absolutePos(fcsPos), h.absolutePos(nearMount))) h.fail("near mount linked without a link");
        // 4. Shift+ПКМ в воздух - сброс настройки
        p.setShiftKeyDown(true);
        p.getMainHandItem().getItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        p.setShiftKeyDown(false);
        if (RadarLinkItem.network(p.getMainHandItem()) != null) h.fail("Shift+use did not clear the tuning");

        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16);
        AtomicInteger hits = countHits(h, husk);
        logEvery(h, be, "link_mount", 40);
        h.succeedWhen(() -> {
            if (be.mountStates().size() != 1 || !be.mountStates().get(0).pos.equals(h.absolutePos(farMount))) {
                throw new GameTestAssertException("computer controls " + be.mountStates().size() + " mounts, expected only the linked one");
            }
            if (!other.mountStates().isEmpty()) throw new GameTestAssertException("computer without links took a mount");
            if (hits.get() == 0) throw new GameTestAssertException("target not hit yet");
            FcsMod.LOG.info("[radar_fcs test] link_mount: linked mount 29 blocks away fired, hits {}", hits.get());
        });
    }

    /** Полный сигнал редстоуна отключает линк (станок выпадает из сети), снятие сигнала - подключает снова. */
    @GameTest(template = ARENA, batch = "link_mount", timeoutTicks = 300)
    public static void redstoneDisablesLink(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 52), fcsPos = new BlockPos(4, 1, 52);
        autocannon(h, mount, Direction.EAST, 2, 8);
        FireControlBlockEntity be = fcsBare(h, fcsPos);
        RadarLinkBlockEntity link = link(h, mount, Direction.NORTH, fcsPos);
        BlockPos linkRel = mount.relative(Direction.NORTH), redstone = linkRel.relative(Direction.NORTH);
        AtomicInteger phase = new AtomicInteger();
        h.runAtTickTime(40, () -> {
            if (be.mountStates().size() != 1) h.fail("mount not connected before the signal: " + be.mountStates().size());
            h.setBlock(redstone, net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState());
            phase.set(1);
        });
        h.runAtTickTime(80, () -> {
            if (LinkRegistry.linked(h.getLevel(), h.absolutePos(fcsPos), h.absolutePos(mount))) h.fail("powered link still connects");
            if (!be.mountStates().isEmpty()) h.fail("computer still controls the mount of a powered link");
            if (!link.describe().getString().contains(RadarLinkBlockEntity.shortName(link.network()))) h.fail("no network in the description");
            h.setBlock(redstone, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            phase.set(2);
        });
        h.succeedWhen(() -> {
            if (phase.get() < 2) throw new GameTestAssertException("waiting");
            if (be.mountStates().size() != 1) throw new GameTestAssertException("mount not reconnected yet");
        });
    }

    /**
     * Ручное наведение с монитора Create Radars: монитор в сети вычислителя, игрок выбрал на нём цель. Радара в сети нет -
     * вычислитель ведёт выбранную цель сам и попадает; пока цель не выбрана - «нет радара», огня нет.
     */
    @GameTest(template = ARENA, batch = "link_crmonitor", timeoutTicks = 500)
    public static void createRadarsMonitorSelectionAims(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28), monPos = new BlockPos(4, 1, 36);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = FcsGameTests.fcs(h, fcsPos);
        h.setBlock(monPos, block("create_radar:monitor").defaultBlockState());
        FcsGameTests.CREATED.add(h.absolutePos(monPos));
        link(h, monPos, Direction.UP, fcsPos);
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16);
        AtomicInteger hits = countHits(h, husk);
        logEvery(h, be, "link_crmonitor", 40);
        String id = husk.getUUID().toString();
        h.runAtTickTime(60, () -> {
            if (be.mountStates().isEmpty() || be.mountStates().get(0).status != Status.NO_RADAR) {
                h.fail("expected NO_RADAR before a selection: " + (be.mountStates().isEmpty() ? "no mounts" : be.mountStates().get(0).status));
            }
            if (hits.get() > 0) h.fail("fired without a radar or a selection");
        });
        // выбор игрока держится на мониторе (Create Radars снимает его, если цели нет в его сети - тест выбирает каждый тик)
        h.onEachTick(() -> {
            if (h.getTick() < 70) return;
            BlockEntity mon = h.getLevel().getBlockEntity(h.absolutePos(monPos));
            try {
                if (mon != null) mon.getClass().getField("selectedEntity").set(mon, id);
            } catch (ReflectiveOperationException e) {
                h.fail("Create Radars monitor has no selectedEntity: " + e);
            }
        });
        h.succeedWhen(() -> {
            if (h.getTick() < 70) throw new GameTestAssertException("waiting for the selection");
            if (!id.equals(be.currentDesignation())) throw new GameTestAssertException("selection not read: " + be.currentDesignation());
            if (hits.get() == 0) throw new GameTestAssertException("selected target not hit yet");
            FcsMod.LOG.info("[radar_fcs test] link_crmonitor: Create Radars monitor selection engaged without a radar, hits {}", hits.get());
        });
    }

    /**
     * Плоский радар Create Radars в двух сетях: A (вычислитель A, его станок, монитор) и B (вычислитель B, его станок).
     * Монитор показывает цели радара; назначение цели с монитора получает только A, вычислитель B его не видит.
     * Снятие назначения с экрана монитора возвращает A к выбору по настройкам.
     */
    @GameTest(template = ARENA, batch = "link_monitor", timeoutTicks = 500)
    public static void monitorDesignatesForLinkedComputer(GameTestHelper h) {
        floor(h);
        BlockPos mountA = new BlockPos(10, 1, 32), mountB = new BlockPos(10, 1, 52);
        bigCannon(h, mountA, Direction.EAST, 2, 4, true);
        bigCannon(h, mountB, Direction.EAST, 2, 4, true);
        BlockPos aPos = new BlockPos(6, 1, 28), bPos = new BlockPos(6, 1, 56), radar = new BlockPos(8, 1, 40);
        FireControlBlockEntity a = fcsBare(h, aPos);
        FireControlBlockEntity b = fcsBare(h, bPos);
        linkMount(h, mountA, aPos);
        linkMount(h, mountB, bPos);
        Player p = h.makeMockPlayer(GameType.CREATIVE);
        a.toggleHold(p);
        b.toggleHold(p);
        // плоский радар сканирует в сторону, противоположную FACING: FACING=запад -> обзор на восток
        h.setBlock(radar, block("create_radar:plane_radar").defaultBlockState()
                .trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        FcsGameTests.CREATED.add(h.absolutePos(radar));
        BlockState mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH);
        for (int x = 2; x <= 3; x++) for (int y = 2; y <= 3; y++) {
            h.setBlock(new BlockPos(x, y, 44), mon);
            FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(x, y, 44)));
        }
        link(h, radar, Direction.UP, aPos);
        link(h, radar, Direction.NORTH, bPos);
        link(h, new BlockPos(3, 3, 44), Direction.UP, aPos);
        Mob near = movingTarget(h, EntityType.HUSK, new Vec3(28.5, 1, 37.5), 0.1, 6);
        Mob far = movingTarget(h, EntityType.HUSK, new Vec3(44.5, 1, 40.5), 0.1, 6);
        logEvery(h, a, "link_monitor A", 40);
        logEvery(h, b, "link_monitor B", 40);
        AtomicBoolean designated = new AtomicBoolean();
        h.runAtTickTime(120, () -> {
            if (!(h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 44))) instanceof RadarMonitorBlockEntity m)) {
                h.fail("monitor missing");
                return;
            }
            if (m.radarCount() != 1) h.fail("monitor sees " + m.radarCount() + " radars, expected the linked one");
            long active = m.blips().stream().filter(x -> (x.flags() & (RadarMonitorBlockEntity.FLAG_INACTIVE | RadarMonitorBlockEntity.FLAG_FRIENDLY)) == 0).count();
            if (active < 2) h.fail("monitor shows " + active + " active blips, expected 2 targets: " + m.blips());
            if (m.blips().stream().anyMatch(x -> x.name().isEmpty() || x.id().isEmpty())) h.fail("blip without a name or id");
            Vec3 pt = m.screenPointFor(far.getBoundingBox().getCenter());
            BlockPos hitBlock = BlockPos.containing(pt.subtract(0, 0, 0.01));
            if (!(h.getLevel().getBlockEntity(hitBlock) instanceof RadarMonitorBlockEntity clicked)) {
                h.fail("click point " + pt + " is not on the monitor");
                return;
            }
            clicked.click(p, new BlockHitResult(pt, Direction.SOUTH, hitBlock, false));
            UUID des = m.localDesignation(h.getLevel().getGameTime());
            if (!far.getUUID().equals(des)) h.fail("monitor did not designate the far target: " + des);
            designated.set(true);
        });
        String farId = far.getUUID().toString();
        AtomicInteger bOnFar = new AtomicInteger();
        h.onEachTick(() -> {
            if (designated.get()) for (MountState m : b.mountStates()) if (farId.equals(m.targetId)) bOnFar.incrementAndGet();
        });
        h.succeedWhen(() -> {
            if (!designated.get()) throw new GameTestAssertException("not designated yet");
            MountState ma = a.mountStates().isEmpty() ? null : a.mountStates().get(0);
            if (ma == null || !farId.equals(ma.targetId)) throw new GameTestAssertException("computer A is not on the designated target");
            if (ma.status != Status.HOLD && ma.status != Status.AIMING) throw new GameTestAssertException("A status " + ma.status);
            if (bOnFar.get() > 0) throw new GameTestAssertException("computer B (monitor not linked) followed the designation");
            if (b.mountStates().isEmpty() || b.mountStates().get(0).targetId == null) throw new GameTestAssertException("B has no target");
            if (h.getTick() < 170) throw new GameTestAssertException("watching computer B");
            // снять назначение (кнопка экрана монитора)
            if (h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 44))) instanceof RadarMonitorBlockEntity m) {
                m.designate(null, null);
                if (m.localDesignation(h.getLevel().getGameTime()) != null) throw new GameTestAssertException("designation not cleared");
            }
            if (a.currentDesignation() != null) throw new GameTestAssertException("A still sees a designation");
            FcsMod.LOG.info("[radar_fcs test] link_monitor: designation went to the linked computer only (near {})", near.getUUID());
        });
    }

    /**
     * Ручное наведение с карты своего монитора: две цели, без назначения пушка бьёт ближнюю (по умолчанию - ближайшая).
     * Клик по дальней на карте - пушка переводится на неё и попадает; по ближней после этого не стреляет.
     */
    @GameTest(template = ARENA, batch = "link_monitor_fire", timeoutTicks = 600)
    public static void monitorDesignationIsEngaged(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28), radar = new BlockPos(8, 1, 40);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcsBare(h, fcsPos);
        linkMount(h, mount, fcsPos);
        h.setBlock(radar, block("create_radar:plane_radar").defaultBlockState()
                .trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        FcsGameTests.CREATED.add(h.absolutePos(radar));
        link(h, radar, Direction.UP, fcsPos);
        BlockState mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH);
        for (int x = 2; x <= 3; x++) for (int y = 2; y <= 3; y++) {
            h.setBlock(new BlockPos(x, y, 44), mon);
            FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(x, y, 44)));
        }
        link(h, new BlockPos(3, 3, 44), Direction.UP, fcsPos);
        Player p = h.makeMockPlayer(GameType.CREATIVE);
        Mob near = movingTarget(h, EntityType.HUSK, new Vec3(26.5, 1, 30.5), 0.05, 4);
        Mob far = movingTarget(h, EntityType.HUSK, new Vec3(44.5, 1, 36.5), 0.1, 6);
        for (Mob mob : new Mob[]{near, far}) {
            var hp = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(1000);                              // живут весь тест
            mob.setHealth(mob.getMaxHealth());
        }
        AtomicInteger nearHits = countHits(h, near), farHits = countHits(h, far);
        logEvery(h, be, "link_monitor_fire", 40);
        AtomicInteger clickTick = new AtomicInteger(-1), nearAtClick = new AtomicInteger();
        h.onEachTick(() -> {
            if (clickTick.get() >= 0 || nearHits.get() == 0) return;
            // пушка уже бьёт ближнюю: клик по дальней на карте монитора
            if (!(h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 44))) instanceof RadarMonitorBlockEntity m)) {
                h.fail("monitor missing");
                return;
            }
            Vec3 pt = m.master().screenPointFor(far.getBoundingBox().getCenter());
            BlockPos hitBlock = BlockPos.containing(pt.subtract(0, 0, 0.01));
            if (!(h.getLevel().getBlockEntity(hitBlock) instanceof RadarMonitorBlockEntity clicked)) {
                h.fail("far target is not on the map: click point " + pt);
                return;
            }
            clicked.click(p, new BlockHitResult(pt, Direction.SOUTH, hitBlock, false));
            if (!far.getUUID().equals(m.master().localDesignation(h.getLevel().getGameTime()))) h.fail("click did not designate the far target");
            clickTick.set((int) h.getTick());
            nearAtClick.set(nearHits.get());
        });
        AtomicInteger nearLate = new AtomicInteger();
        h.onEachTick(() -> {
            // снаряды, выпущенные до клика, ещё долетают: считаем попадания по ближней через 30 тиков после него
            if (clickTick.get() >= 0 && h.getTick() == clickTick.get() + 30) nearLate.set(nearHits.get());
        });
        String farId = far.getUUID().toString();
        h.succeedWhen(() -> {
            if (clickTick.get() < 0) throw new GameTestAssertException("near target not engaged yet (hits " + nearHits.get() + ")");
            MountState ms = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            if (ms == null || !farId.equals(ms.targetId)) throw new GameTestAssertException("mount is not on the designated target");
            if (h.getTick() < clickTick.get() + 30) throw new GameTestAssertException("waiting");
            if (farHits.get() < 2) throw new GameTestAssertException("designated target hit " + farHits.get() + " times");
            if (nearHits.get() > nearLate.get()) h.fail("kept firing at the near target after the designation: "
                    + nearLate.get() + " -> " + nearHits.get());
            FcsMod.LOG.info("[radar_fcs test] link_monitor_fire: near hit {} before the click, designated far hit {} after it, near after: {}",
                    nearAtClick.get(), farHits.get(), nearHits.get() - nearLate.get());
        });
    }

    /**
     * Исключение цели с монитора (нажатие колеса): пушка перестаёт по ней стрелять; повторное нажатие - огонь снова.
     * Исключённая цель ушла из зоны радара и вернулась - исключение снято само, огонь снова.
     */
    @GameTest(template = ARENA, batch = "link_monitor_exclude", timeoutTicks = 1000)
    public static void monitorExclusionStopsFire(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28), radar = new BlockPos(8, 1, 40);
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcsBare(h, fcsPos);
        SettingsGameTests.configure(be, st -> st.dropStatic = false);
        linkMount(h, mount, fcsPos);
        h.setBlock(radar, block("create_radar:plane_radar").defaultBlockState()
                .trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        FcsGameTests.CREATED.add(h.absolutePos(radar));
        link(h, radar, Direction.UP, fcsPos);
        BlockState mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH);
        for (int x = 2; x <= 3; x++) for (int y = 2; y <= 3; y++) {
            h.setBlock(new BlockPos(x, y, 44), mon);
            FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(x, y, 44)));
        }
        link(h, new BlockPos(3, 3, 44), Direction.UP, fcsPos);
        Player p = h.makeMockPlayer(GameType.CREATIVE);
        Mob husk = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(38.5, 1, 36.5), 0, 0));
        AtomicInteger hits = countHits(h, husk);
        logEvery(h, be, "link_monitor_exclude", 40);
        String id = husk.getUUID().toString();
        AtomicInteger phase = new AtomicInteger(), mark = new AtomicInteger(), since = new AtomicInteger();
        java.util.List<String> log = new java.util.ArrayList<>();
        h.onEachTick(() -> {
            if (!(h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 44))) instanceof RadarMonitorBlockEntity m0)) return;
            RadarMonitorBlockEntity m = m0.master();
            int t = (int) h.getTick();
            MountState ms = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            switch (phase.get()) {
                case 0 -> {                                                  // огонь по цели, затем исключить
                    if (hits.get() >= 2) {
                        m.toggleExclusion(husk.getUUID(), p);
                        if (!m.exclusions().contains(husk.getUUID())) h.fail("target not excluded");
                        log.add("excluded after " + hits.get() + " hits at t=" + t);
                        since.set(t);
                        phase.set(1);
                    }
                }
                case 1 -> {                                                  // огня нет 100 тиков
                    if (t == since.get() + 25) mark.set(hits.get());          // летящие снаряды долетели
                    if (t > since.get() + 25) {
                        if (hits.get() > mark.get()) h.fail("hit while excluded: " + mark.get() + " -> " + hits.get());
                        if (ms != null && id.equals(ms.targetId)) h.fail("mount still on the excluded target");
                    }
                    if (t == since.get() + 30 && m.blips().stream().noneMatch(b -> b.id().equals(id)
                            && (b.flags() & RadarMonitorBlockEntity.FLAG_EXCLUDED) != 0)) h.fail("blip not marked as excluded");
                    if (t == since.get() + 125) {
                        m.toggleExclusion(husk.getUUID(), p);                 // повторное нажатие - вернуть
                        if (!m.exclusions().isEmpty()) h.fail("exclusion not cleared by the second press");
                        log.add("restored at t=" + t + ", hits " + hits.get());
                        mark.set(hits.get());
                        phase.set(2);
                    }
                }
                case 2 -> {                                                  // огонь снова - исключить и увести из зоны
                    if (hits.get() > mark.get()) {
                        log.add("fire resumed, hits " + hits.get() + " at t=" + t);
                        m.toggleExclusion(husk.getUUID(), p);
                        husk.teleportTo(h.absoluteVec(new Vec3(30.5, 1, 4.5)).x, husk.getY(), h.absoluteVec(new Vec3(30.5, 1, 4.5)).z);
                        since.set(t);
                        phase.set(3);
                    }
                }
                case 3 -> {                                                  // вне зоны радара: исключение снимается само
                    if ((t - since.get()) % 40 == 0) {
                        var rbe = h.getLevel().getBlockEntity(h.absolutePos(radar));
                        var cs = rbe == null ? null : dev.radarfcs.compat.RadarLink.contacts(rbe);
                        FcsMod.LOG.info("[radar_fcs test] link_monitor_exclude out t+{}: husk at {}, radar contacts {}, blip {}",
                                t - since.get(), husk.position(), cs == null ? null : cs.stream().map(c -> c.id().equals(id) ? "HUSK" : c.id()).toList(),
                                m.blips().stream().anyMatch(b -> b.id().equals(id)));
                    }
                    if (m.exclusions().isEmpty()) {
                        log.add("exclusion dropped " + (t - since.get()) + " ticks after leaving the radar");
                        Vec3 back = h.absoluteVec(new Vec3(38.5, 1, 36.5));
                        husk.teleportTo(back.x, back.y, back.z);
                        mark.set(hits.get());
                        since.set(t);
                        phase.set(4);
                    } else if (t > since.get() + 300) h.fail("exclusion kept after the target left the radar");
                }
                case 4 -> {
                    if (hits.get() > mark.get()) {
                        log.add("back in coverage: hit after " + (t - since.get()) + " ticks");
                        phase.set(5);
                    }
                }
                default -> { }
            }
        });
        h.succeedWhen(() -> {
            if (phase.get() < 5) throw new GameTestAssertException("phase " + phase.get() + ": " + log);
            FcsMod.LOG.info("[radar_fcs test] link_monitor_exclude: {}", log);
        });
    }

    /**
     * Метки на карте и сторона карты: метки ставятся, правятся (имя чистится), убираются, не больше MAX_PINS,
     * сохраняются в мир вместе со стороной карты; сторона - до 1 000 000 блоков.
     */
    @GameTest(template = ARENA, batch = "monitor_pins", timeoutTicks = 100)
    public static void monitorPinsAndSide(GameTestHelper h) {
        floor(h);
        BlockPos pos = new BlockPos(4, 2, 10);
        h.setBlock(pos, FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH));
        FcsGameTests.CREATED.add(h.absolutePos(pos));
        Player p = h.makeMockPlayer(GameType.CREATIVE);
        h.runAtTickTime(25, () -> {
            if (!(h.getLevel().getBlockEntity(h.absolutePos(pos)) instanceof RadarMonitorBlockEntity m)) {
                h.fail("monitor missing");
                return;
            }
            m.setSide(1_000_000);
            if (m.mapSide() != 1_000_000) h.fail("side 1000000 not accepted: " + m.mapSide());
            m.setSide(5_000_000);
            if (m.mapSide() != RadarMonitorBlockEntity.MAX_SIDE) h.fail("side not clamped: " + m.mapSide());
            m.setSide(3);
            if (m.mapSide() != RadarMonitorBlockEntity.MIN_SIDE) h.fail("side not clamped low: " + m.mapSide());
            m.setSide(750_000);
            m.setPin(-1, "100;-200;3;База", p);
            m.setPin(-1, "-5;7;0;§cКрасная;точка", p);                 // цвет 0 -> палитра, формат и «;» убираются
            if (m.pins().size() != 2) h.fail("pins not added: " + m.pins());
            var second = m.pins().get(1);
            if (second.color() < 1 || second.name().contains("§") || second.name().contains(";")) h.fail("pin not cleaned: " + second);
            m.setPin(0, "101;-201;4;Порт", p);
            if (!m.pins().get(0).equals(new RadarMonitorBlockEntity.Pin(101, -201, "Порт", 4))) h.fail("pin not edited: " + m.pins().get(0));
            m.removePin(1);
            if (m.pins().size() != 1) h.fail("pin not removed: " + m.pins());
            for (int k = 0; k < 40; k++) m.setPin(-1, k + ";" + k + ";1;", p);
            if (m.pins().size() != RadarMonitorBlockEntity.MAX_PINS) h.fail("pin limit not kept: " + m.pins().size());
            // сохранение в мир и загрузка
            var reg = h.getLevel().registryAccess();
            var tag = m.saveWithoutMetadata(reg);
            RadarMonitorBlockEntity copy = new RadarMonitorBlockEntity(m.getBlockPos(), m.getBlockState());
            copy.loadWithComponents(tag, reg);
            if (!copy.pins().equals(m.pins())) h.fail("pins not saved: " + copy.pins().size());
            if (copy.mapSide() != 750_000) h.fail("side not saved: " + copy.mapSide());
            if ((copy.show() & RadarMonitorBlockEntity.Show.PINS.bit) == 0) h.fail("pins filter off by default");
            FcsMod.LOG.info("[radar_fcs test] monitor_pins: side {}, pins {}, first {}", copy.mapSide(), copy.pins().size(), copy.pins().get(0));
            h.succeed();
        });
    }

    /**
     * Радарный линк не уезжает с антенной: отражатель Create Radars «липкий» и при сборке забирает соседние блоки
     * (контроль - наблюдатель под отражателем на втором подшипнике уезжает), а линк под отражателем остаётся на месте.
     */
    @GameTest(template = ARENA, batch = "link_sticky", timeoutTicks = 100)
    public static void radarLinkDoesNotStickToDish(GameTestHelper h) {
        floor(h);
        BlockPos linkBearing = new BlockPos(5, 2, 5), ctrlBearing = new BlockPos(5, 2, 12);
        for (BlockPos b : new BlockPos[]{linkBearing, ctrlBearing}) {
            h.setBlock(b.below(), block("create:creative_motor").defaultBlockState().trySetValue(BlockStateProperties.FACING, Direction.UP));
            if (h.getLevel().getBlockEntity(h.absolutePos(b.below())) instanceof com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity mo) {
                mo.generatedSpeed.setValue(8);
            }
            h.setBlock(b, block("create:mechanical_bearing").defaultBlockState().trySetValue(BlockStateProperties.FACING, Direction.UP));
            h.setBlock(b.above(), block("create_radar:radar_dish_block").defaultBlockState());
            h.setBlock(b.above().east(), block("create_radar:radar_dish_block").defaultBlockState());
        }
        // линк на боковой грани подшипника - прямо под отражателем; у контроля там наблюдатель
        h.setBlock(linkBearing.east(), FcsRegistry.RADAR_LINK.get().defaultBlockState()
                .setValue(dev.radarfcs.block.RadarLinkBlock.FACING, Direction.EAST));
        FcsGameTests.CREATED.add(h.absolutePos(linkBearing.east()));
        h.setBlock(ctrlBearing.east(), net.minecraft.world.level.block.Blocks.OBSERVER.defaultBlockState());
        h.runAtTickTime(5, () -> {
            for (BlockPos b : new BlockPos[]{linkBearing, ctrlBearing}) {
                var be = h.getLevel().getBlockEntity(h.absolutePos(b));
                try {
                    if (be != null) be.getClass().getMethod("assemble").invoke(be);
                } catch (ReflectiveOperationException e) {
                    h.fail("bearing assemble: " + e);
                }
            }
        });
        h.runAtTickTime(15, () -> {
            if (!h.getBlockState(linkBearing.above().east()).isAir() || !h.getBlockState(ctrlBearing.above().east()).isAir()) {
                var be = h.getLevel().getBlockEntity(h.absolutePos(linkBearing));
                Object err = null;
                try {
                    var f = com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity.class.getDeclaredField("lastException");
                    f.setAccessible(true);
                    err = f.get(be);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    err = e;
                }
                h.fail("dishes were not assembled onto the bearings: " + err);
            }
            if (!h.getBlockState(ctrlBearing.east()).isAir()) h.fail("control: the observer did not stick to the dish (no sticky dish?)");
            if (!(h.getBlockState(linkBearing.east()).getBlock() instanceof dev.radarfcs.block.RadarLinkBlock)) {
                h.fail("radar link was carried away with the dish: " + h.getBlockState(linkBearing.east()));
            }
            FcsMod.LOG.info("[radar_fcs test] link_sticky: dish assembled, observer carried away, radar link stayed");
            h.succeed();
        });
    }

    /**
     * Лимит сети: 16 линков любой аппаратуры + 16 за каждый блок памяти с питанием. 18 мониторов в одной сети -
     * работают 16 линков (два последних - «сеть переполнена»), блок памяти в сети - работают все.
     */
    @GameTest(template = ARENA, batch = "link_limit", timeoutTicks = 200)
    public static void networkLinkLimit(GameTestHelper h) {
        floor(h);
        BlockPos fcsPos = new BlockPos(4, 1, 4);
        fcsBare(h, fcsPos);
        BlockState mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH);
        java.util.List<BlockPos> monitors = new java.util.ArrayList<>();
        for (int i = 0; i < 17; i++) {
            BlockPos p = new BlockPos(4 + (i % 9) * 3, 2, 12 + (i / 9) * 4);
            h.setBlock(p, mon);
            FcsGameTests.CREATED.add(h.absolutePos(p));
            monitors.add(p);
        }
        // по одному линку за тик - порядок подключения задан
        for (int i = 0; i < monitors.size(); i++) {
            BlockPos p = monitors.get(i);
            h.runAtTickTime(2 + i, () -> link(h, p, Direction.UP, fcsPos));
        }
        BlockPos memPos = new BlockPos(30, 1, 4);
        h.runAtTickTime(60, () -> {
            var level = h.getLevel();
            java.util.UUID net = level.getBlockEntity(h.absolutePos(monitors.get(0).above())) instanceof RadarLinkBlockEntity l ? l.network() : null;
            if (net == null) {
                h.fail("no network on the computer link");
                return;
            }
            int[] u = LinkRegistry.usage(level, net);
            if (u[0] != 18 || u[1] != 16) h.fail("usage " + u[0] + "/" + u[1] + ", expected 18/16");
            int inactive = 0;
            for (BlockPos p : monitors) if (!LinkRegistry.isActive(level, h.absolutePos(p.above()))) inactive++;
            if (inactive != 2) h.fail("inactive monitor links " + inactive + ", expected 2 (the last two connected)");
            if (LinkRegistry.isActive(level, h.absolutePos(monitors.get(16).above()))) h.fail("the last connected link must be the inactive one");
            if (LinkRegistry.members(level, net).size() != 16) h.fail("members " + LinkRegistry.members(level, net).size() + ", expected 16");
            // блок памяти: +16
            h.setBlock(memPos, FcsRegistry.MEMORY.get().defaultBlockState());
            FcsGameTests.CREATED.add(h.absolutePos(memPos));
            link(h, memPos, Direction.UP, fcsPos);
        });
        h.runAtTickTime(80, () -> {
            var level = h.getLevel();
            for (BlockPos p : monitors) if (!LinkRegistry.isActive(level, h.absolutePos(p.above()))) h.fail("monitor link still inactive with a memory block: " + p);
            FcsMod.LOG.info("[radar_fcs test] link_limit: 18 links -> 16 active, with a memory block -> all active");
            h.succeed();
        });
    }
}
