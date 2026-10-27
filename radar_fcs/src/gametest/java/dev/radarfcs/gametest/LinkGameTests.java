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
}
