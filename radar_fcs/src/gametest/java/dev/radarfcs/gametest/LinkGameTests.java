package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.bigCannon;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.RadarDomeBlockEntity;
import dev.radarfcs.block.RadarLinkBlock;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.block.RadarMonitorBlock;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.item.RadarLinkItem;
import dev.radarfcs.link.LinkRegistry;
import dev.radarfcs.radar.RadarNetwork;
import dev.radarfcs.registry.FcsRegistry;
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
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Радарный линк: подключение станка за радиусом, радара другого канала, монитора с личным назначением цели. */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class LinkGameTests {

    private static final String ARENA = "arena";

    @BeforeBatch(batch = "link_mount") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "link_mount") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "link_radar") public static void b2(ServerLevel l) { RadarGameTests.radarConfig(l); }
    @AfterBatch(batch = "link_radar") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "link_monitor") public static void b3(ServerLevel l) { RadarGameTests.radarConfig(l); }
    @AfterBatch(batch = "link_monitor") public static void a3(ServerLevel l) { FcsGameTests.cleanup(l); }

    // ----------------------------------------------------------------- помощники

    /** ПКМ предметом в руке игрока по грани блока (как в игре). */
    static InteractionResult use(GameTestHelper h, Player p, BlockPos rel, Direction face) {
        BlockPos abs = h.absolutePos(rel);
        Vec3 at = Vec3.atCenterOf(abs).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        return p.getMainHandItem().getItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(at, face, abs, false)));
    }

    /** Линк на грани face блока attachedRel, второй конец - otherRel (без игрока). */
    static RadarLinkBlockEntity link(GameTestHelper h, BlockPos attachedRel, Direction face, BlockPos otherRel) {
        BlockPos at = attachedRel.relative(face);
        h.setBlock(at, FcsRegistry.RADAR_LINK.get().defaultBlockState().setValue(RadarLinkBlock.FACING, face));
        FcsGameTests.CREATED.add(h.absolutePos(at));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(at)) instanceof RadarLinkBlockEntity be)) {
            throw new GameTestAssertException("no radar link at " + at);
        }
        be.setOther(h.absolutePos(otherRel));
        return be;
    }

    // ------------------------------------------------------------------- тесты

    /**
     * Станок в 29 блоках (дальше linkRadius) подключён линком, поставленным предметом: вычислитель управляет только им,
     * станок рядом без линка не берёт; второй вычислитель рядом с подключённым станком его не перехватывает.
     */
    @GameTest(template = ARENA, batch = "link_mount", timeoutTicks = 500)
    public static void linkedMountBeyondRadius(GameTestHelper h) {
        floor(h);
        BlockPos farMount = new BlockPos(10, 1, 32), nearMount = new BlockPos(10, 1, 8), fcsPos = new BlockPos(4, 1, 4);
        autocannon(h, farMount, Direction.EAST, 4, 64);
        autocannon(h, nearMount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, fcsPos);
        FireControlBlockEntity other = fcs(h, new BlockPos(6, 1, 36));       // рядом с дальним станком, без линков

        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FcsRegistry.RADAR_LINK_ITEM.get(), 4));
        if (!use(h, p, fcsPos, Direction.UP).consumesAction()) h.fail("selecting the computer failed");
        if (!h.absolutePos(fcsPos).equals(RadarLinkItem.selection(p.getMainHandItem(), h.getLevel()))) h.fail("computer not selected");
        if (!use(h, p, farMount, Direction.WEST).consumesAction()) h.fail("placing the link on the mount failed");
        BlockPos linkAt = farMount.relative(Direction.WEST);
        FcsGameTests.CREATED.add(h.absolutePos(linkAt));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(linkAt)) instanceof RadarLinkBlockEntity)) h.fail("no link block placed");
        if (!LinkRegistry.linked(h.getLevel(), h.absolutePos(fcsPos), h.absolutePos(farMount))) h.fail("link not registered");
        if (RadarLinkItem.selection(p.getMainHandItem(), h.getLevel()) != null) h.fail("selection not cleared after linking");

        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16);
        AtomicInteger hits = countHits(h, husk);
        logEvery(h, be, "link_mount", 40);
        h.succeedWhen(() -> {
            if (be.mountStates().size() != 1 || !be.mountStates().get(0).pos.equals(h.absolutePos(farMount))) {
                throw new GameTestAssertException("computer controls " + be.mountStates().size() + " mounts, expected only the linked one");
            }
            if (!other.mountStates().isEmpty()) throw new GameTestAssertException("other computer took the linked mount");
            if (hits.get() == 0) throw new GameTestAssertException("target not hit yet");
            FcsMod.LOG.info("[radar_fcs test] link_mount: linked mount 29 blocks away fired, hits {}", hits.get());
        });
    }

    /** Радар другого канала: без линка вычислитель его не видит («нет радара»), с линком - видит и бьёт. */
    @GameTest(template = ARENA, batch = "link_radar", timeoutTicks = 600)
    public static void linkedRadarIgnoresChannel(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        BlockPos fcsPos = new BlockPos(6, 1, 28), domePos = new BlockPos(8, 1, 38);
        FireControlBlockEntity be = fcs(h, fcsPos);
        h.setBlock(domePos, FcsRegistry.RADAR_DOME.get().defaultBlockState());
        FcsGameTests.CREATED.add(h.absolutePos(domePos));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(domePos)) instanceof RadarDomeBlockEntity dome)) throw new GameTestAssertException("no dome");
        dome.setChannel(DyeColor.RED, h.makeMockPlayer(GameType.CREATIVE));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 26.5), 0.12, 14);
        AtomicInteger hits = countHits(h, husk);
        logEvery(h, be, "link_radar", 40);
        AtomicBoolean linked = new AtomicBoolean();
        h.runAtTickTime(60, () -> {
            for (MountState m : be.mountStates()) if (m.status != Status.NO_RADAR) h.fail("expected NO_RADAR before linking, got " + m.status);
            link(h, domePos, Direction.WEST, fcsPos);
            linked.set(true);
        });
        h.succeedWhen(() -> {
            if (!linked.get()) throw new GameTestAssertException("not linked yet");
            if (be.linkedRadars().size() != 1) throw new GameTestAssertException("radars " + be.linkedRadars().size());
            if (hits.get() == 0) throw new GameTestAssertException("target not hit yet");
            FcsMod.LOG.info("[radar_fcs test] link_radar: red dome on a link guides the white computer, hits {}", hits.get());
        });
    }

    /**
     * Монитор на линке с вычислителем A: назначение цели с него идёт только A (не на весь канал);
     * вычислитель B того же канала его не получает.
     */
    @GameTest(template = ARENA, batch = "link_monitor", timeoutTicks = 400)
    public static void linkedMonitorDesignatesPrivately(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        bigCannon(h, new BlockPos(10, 1, 52), Direction.EAST, 2, 4, true);
        BlockPos aPos = new BlockPos(6, 1, 28);
        FireControlBlockEntity a = fcs(h, aPos);
        FireControlBlockEntity b = fcs(h, new BlockPos(6, 1, 56));
        Player p = h.makeMockPlayer(GameType.CREATIVE);
        a.toggleHold(p);
        b.toggleHold(p);
        h.setBlock(new BlockPos(8, 1, 40), FcsRegistry.RADAR_DOME.get().defaultBlockState());
        FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(8, 1, 40)));
        BlockState mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH);
        for (int x = 2; x <= 3; x++) for (int y = 2; y <= 3; y++) {
            h.setBlock(new BlockPos(x, y, 44), mon);
            FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(x, y, 44)));
        }
        link(h, aPos, Direction.UP, new BlockPos(3, 2, 44));
        Mob near = movingTarget(h, EntityType.HUSK, new Vec3(28.5, 1, 30.5), 0.1, 6);
        Mob far = movingTarget(h, EntityType.HUSK, new Vec3(44.5, 1, 40.5), 0.1, 6);
        logEvery(h, a, "link_monitor A", 40);
        logEvery(h, b, "link_monitor B", 40);
        AtomicBoolean designated = new AtomicBoolean();
        h.runAtTickTime(90, () -> {
            if (!(h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 44))) instanceof RadarMonitorBlockEntity m)) {
                h.fail("monitor missing");
                return;
            }
            Vec3 pt = m.screenPointFor(far.getBoundingBox().getCenter());
            BlockPos hitBlock = BlockPos.containing(pt.subtract(0, 0, 0.01));
            if (!(h.getLevel().getBlockEntity(hitBlock) instanceof RadarMonitorBlockEntity clicked)) {
                h.fail("click point " + pt + " is not on the monitor");
                return;
            }
            clicked.click(p, new BlockHitResult(pt, Direction.SOUTH, hitBlock, false));
            long now = h.getLevel().getGameTime();
            if (!far.getUUID().equals(m.localDesignation(now))) h.fail("monitor did not designate the far target");
            if (RadarNetwork.designated(h.getLevel(), DyeColor.WHITE.getId(), now) != null) h.fail("designation leaked to the channel");
            designated.set(true);
        });
        String farId = far.getUUID().toString();
        AtomicInteger bOnFar = new AtomicInteger();
        h.onEachTick(() -> {
            if (designated.get()) for (MountState m : b.mountStates()) if (farId.equals(m.targetId)) bOnFar.incrementAndGet();
        });
        h.succeedWhen(() -> {
            if (!designated.get()) throw new GameTestAssertException("not designated yet");
            if (a.mountStates().isEmpty() || !farId.equals(a.mountStates().get(0).targetId)) {
                throw new GameTestAssertException("computer A is not on the designated target");
            }
            if (bOnFar.get() > 0) h.fail("computer B (not linked) followed the designation");
            if (h.getTick() < 140) throw new GameTestAssertException("watching computer B");
            FcsMod.LOG.info("[radar_fcs test] link_monitor: designation went to the linked computer only (near {})", near.getUUID());
        });
    }
}
