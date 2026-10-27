package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.bigCannon;
import static dev.radarfcs.gametest.FcsGameTests.block;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.PhasedArrayBlockEntity;
import dev.radarfcs.block.RadarBearingBlockEntity;
import dev.radarfcs.block.RadarDomeBlockEntity;
import dev.radarfcs.block.RadarMonitorBlock;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.block.RadarReceiverBlock;
import dev.radarfcs.block.PhasedArrayPanelBlock;
import dev.radarfcs.core.Track;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.radar.RadarContact;
import dev.radarfcs.radar.RadarNetwork;
import dev.radarfcs.radar.RadarStatus;
import dev.radarfcs.registry.FcsRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Игровые тесты радаров мода (без Create Radars): купол, радарный подшипник с антенной-контрапшеном,
 * фазированная решётка (сектор, прямая видимость), монитор (отбор отметок, назначение цели кликом), каналы.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class RadarGameTests {
    private static final String ARENA = "arena";

    static void radarConfig(ServerLevel level) {
        FcsGameTests.cleanup(level);
        FcsGameTests.commonConfig();
        FcsConfig.REQUIRE_RADAR.set(true);
        FcsConfig.SENSOR_MODE.set(FcsConfig.SensorMode.AUTO);
        FcsConfig.NETWORK_RANGE.set(256.0);
        FcsConfig.RADAR_LOS.set(true);
    }

    @BeforeBatch(batch = "dome") public static void b1(ServerLevel l) { radarConfig(l); }
    @AfterBatch(batch = "dome") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "bearing") public static void b2(ServerLevel l) { radarConfig(l); }
    @AfterBatch(batch = "bearing") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "array") public static void b3(ServerLevel l) { radarConfig(l); }
    @AfterBatch(batch = "array") public static void a3(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "monitor") public static void b4(ServerLevel l) { radarConfig(l); }
    @AfterBatch(batch = "monitor") public static void a4(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "channel") public static void b5(ServerLevel l) { radarConfig(l); }
    @AfterBatch(batch = "channel") public static void a5(ServerLevel l) { FcsGameTests.cleanup(l); }

    // ------------------------------------------------------------------ строители

    private static <T extends BlockEntity> T place(GameTestHelper h, BlockPos rel, BlockState state, Class<T> type) {
        h.setBlock(rel, state);
        BlockPos abs = h.absolutePos(rel);
        FcsGameTests.CREATED.add(abs);
        BlockEntity be = h.getLevel().getBlockEntity(abs);
        if (!type.isInstance(be)) throw new GameTestAssertException("no " + type.getSimpleName() + " at " + rel);
        return type.cast(be);
    }

    private static void placeBlock(GameTestHelper h, BlockPos rel, BlockState state) {
        h.setBlock(rel, state);
        FcsGameTests.CREATED.add(h.absolutePos(rel));
    }

    private static RadarDomeBlockEntity dome(GameTestHelper h, BlockPos rel) {
        return place(h, rel, FcsRegistry.RADAR_DOME.get().defaultBlockState(), RadarDomeBlockEntity.class);
    }

    private static Player player(GameTestHelper h) {
        return h.makeMockPlayer(GameType.CREATIVE);
    }

    private static boolean hasContact(Iterable<RadarContact> list, UUID id) {
        for (RadarContact c : list) if (c.id.equals(id)) return true;
        return false;
    }

    // ------------------------------------------------------------------- тесты

    /** Купол: без радара вычислитель стоит («нет радара»), с куполом - цели с купола и попадание. */
    @GameTest(template = ARENA, batch = "dome", timeoutTicks = 700)
    public static void domeRadarGuidesFire(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 26.5), 0.12, 14);
        float max = husk.getMaxHealth();
        logEvery(h, be, "dome", 20);
        AtomicBoolean sawNoRadar = new AtomicBoolean();
        h.runAtTickTime(50, () -> {
            for (MountState m : be.mountStates()) if (m.status == Status.NO_RADAR) sawNoRadar.set(true);
            if (husk.getHealth() < max) h.fail("fired without radar");
            dome(h, new BlockPos(8, 1, 38));
        });
        h.succeedWhen(() -> {
            if (!sawNoRadar.get()) throw new GameTestAssertException("NO_RADAR status not observed before dome placed");
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            if (!be.sensor().usedRadar()) throw new GameTestAssertException("radar contacts were not used");
            FcsMod.LOG.info("[radar_fcs test] dome: HIT via own dome radar, radars={}", be.linkedRadars().size());
        });
    }

    /**
     * Радарный подшипник: творческий мотор крутит подшипник, антенна (облучатель + 3 отражателя без клея)
     * собирается сама. Радар видит цель при проходе луча, вычислитель по ней попадает;
     * своя вращающаяся антенна целью не считается.
     */
    @GameTest(template = ARENA, batch = "bearing", timeoutTicks = 800)
    public static void bearingRadarTracksAndHits(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        BlockPos b = new BlockPos(20, 2, 46);
        placeBlock(h, b.above(), FcsRegistry.RADAR_RECEIVER.get().defaultBlockState().setValue(RadarReceiverBlock.FACING, Direction.EAST));
        placeBlock(h, b.above().north(), FcsRegistry.RADAR_DISH.get().defaultBlockState());
        placeBlock(h, b.above().south(), FcsRegistry.RADAR_DISH.get().defaultBlockState());
        placeBlock(h, b.above(2), FcsRegistry.RADAR_DISH.get().defaultBlockState());
        RadarBearingBlockEntity radar = place(h, b, FcsRegistry.RADAR_BEARING.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP), RadarBearingBlockEntity.class);
        h.setBlock(b.below(), block("create:creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(44.5, 1, 30.5), 0.1, 10);
        float max = husk.getMaxHealth();
        logEvery(h, be, "bearing", 20);
        AtomicBoolean sawContact = new AtomicBoolean();
        h.onEachTick(() -> {
            if (hasContact(radar.radarContacts().all(), husk.getUUID())) sawContact.set(true);
            for (Track t : be.sensor().all()) {
                if (t.kind == Track.Kind.CONTRAPTION) h.fail("own rotating antenna became a track");
            }
        });
        h.succeedWhen(() -> {
            if (radar.status() != RadarStatus.OK) throw new GameTestAssertException("radar status " + radar.status());
            if (radar.dishCount() != 3) throw new GameTestAssertException("dishes " + radar.dishCount() + " != 3");
            if (!sawContact.get()) throw new GameTestAssertException("radar has not seen the target");
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            FcsMod.LOG.info("[radar_fcs test] bearing: HIT, range {}, contacts {}", radar.radarRange(), radar.radarContacts().size());
        });
    }

    /** Фазированная решётка 3x3: видит цель в секторе перед собой, не видит позади и за стеной. */
    @GameTest(template = ARENA, batch = "array", timeoutTicks = 300)
    public static void phasedArraySectorAndCover(GameTestHelper h) {
        floor(h);
        BlockPos c = new BlockPos(30, 2, 10);
        BlockState panel = FcsRegistry.PHASED_ARRAY_PANEL.get().defaultBlockState().setValue(PhasedArrayPanelBlock.FACING, Direction.EAST);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dy != 0 || dz != 0) placeBlock(h, c.offset(0, dy, dz), panel);
            }
        }
        PhasedArrayBlockEntity array = place(h, c, FcsRegistry.PHASED_ARRAY_CONTROLLER.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), PhasedArrayBlockEntity.class);
        h.setBlock(c.west(), block("create:creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
        for (int z = 0; z <= 8; z++) for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(38, y, z), Blocks.STONE.defaultBlockState());
        Mob front = movingTarget(h, EntityType.HUSK, new Vec3(50.5, 1, 12.5), 0.1, 6);
        Mob behind = movingTarget(h, EntityType.HUSK, new Vec3(12.5, 1, 12.5), 0.1, 6);
        Mob hidden = movingTarget(h, EntityType.HUSK, new Vec3(44.5, 1, 3.5), 0.05, 2);
        h.runAtTickTime(120, () -> {
            if (array.status() != RadarStatus.OK) h.fail("array status " + array.status());
            if (array.panelCount() != 8) h.fail("panels " + array.panelCount() + " != 8");
            var all = array.radarContacts().all();
            if (!hasContact(all, front.getUUID())) h.fail("target in front not detected");
            if (hasContact(all, behind.getUUID())) h.fail("target behind the array detected");
            if (hasContact(all, hidden.getUUID())) h.fail("target behind a wall detected");
            float stress = array.calculateStressApplied();
            if (Math.abs(stress - FcsConfig.ARRAY_STRESS.get() * 9) > 1e-3) h.fail("stress impact " + stress);
            FcsMod.LOG.info("[radar_fcs test] array: range {}, contacts {}, stress {}", array.radarRange(), all.size(), stress);
            h.succeed();
        });
    }

    /**
     * Монитор 2x2: показывает только активные чужие цели (неподвижная и защищённая транспондером скрыты),
     * клик по отметке назначает цель, и вычислитель переключается на неё с ближней.
     */
    @GameTest(template = ARENA, batch = "monitor", timeoutTicks = 400)
    public static void monitorShowsAndDesignates(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Player p = player(h);
        be.toggleHold(p);                                                     // наводимся, но не стреляем
        dome(h, new BlockPos(8, 1, 38));
        BlockState mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH);
        for (int x = 2; x <= 3; x++) for (int y = 2; y <= 3; y++) placeBlock(h, new BlockPos(x, y, 44), mon);
        Mob near = movingTarget(h, EntityType.HUSK, new Vec3(28.5, 1, 30.5), 0.1, 6);
        Mob far = movingTarget(h, EntityType.HUSK, new Vec3(44.5, 1, 40.5), 0.1, 6);
        movingTarget(h, EntityType.HUSK, new Vec3(36.5, 1, 20.5), 0, 0);       // неподвижная
        h.setBlock(new BlockPos(24, 1, 46), FcsRegistry.IFF_TRANSPONDER.get().defaultBlockState());
        movingTarget(h, EntityType.HUSK, new Vec3(26.5, 1, 44.5), 0.1, 4);    // под защитой транспондера
        logEvery(h, be, "monitor", 20);
        AtomicReference<String> designated = new AtomicReference<>();
        h.runAtTickTime(90, () -> {
            BlockPos masterAbs = h.absolutePos(new BlockPos(2, 2, 44));
            if (!(h.getLevel().getBlockEntity(masterAbs) instanceof RadarMonitorBlockEntity m)) {
                h.fail("monitor missing");
                return;
            }
            if (!m.isMaster() || m.width() != 2 || m.height() != 2) h.fail("monitor cluster " + m.width() + "x" + m.height());
            if (m.blips().size() != 2) h.fail("monitor shows " + m.blips().size() + " blips, expected 2 moving hostile targets");
            MountState ms = be.mountStates().get(0);
            if (!near.getUUID().toString().equals(ms.targetId)) h.fail("FCS did not pick the nearest target first: " + ms.targetId);
            // клик по отметке дальней цели на экране
            Vec3 pt = m.screenPointFor(far.getBoundingBox().getCenter());
            BlockPos hitBlock = BlockPos.containing(pt.subtract(0, 0, 0.01));
            if (!(h.getLevel().getBlockEntity(hitBlock) instanceof RadarMonitorBlockEntity clicked)) {
                h.fail("click point " + pt + " is not on the monitor");
                return;
            }
            clicked.click(p, new BlockHitResult(pt, Direction.SOUTH, hitBlock, false));
            UUID des = RadarNetwork.designated(h.getLevel(), DyeColor.WHITE.getId(), h.getLevel().getGameTime());
            if (!far.getUUID().equals(des)) h.fail("designation " + des + " != far target");
            designated.set(far.getUUID().toString());
        });
        h.succeedWhen(() -> {
            if (designated.get() == null) throw new GameTestAssertException("not designated yet");
            MountState ms = be.mountStates().get(0);
            if (!designated.get().equals(ms.targetId)) throw new GameTestAssertException("FCS still on " + ms.targetId);
            if (ms.status != Status.HOLD && ms.status != Status.AIMING) throw new GameTestAssertException("status " + ms.status);
            FcsMod.LOG.info("[radar_fcs test] monitor: FCS switched to the designated target");
        });
    }

    /** Каналы: радар красного канала не обслуживает белый вычислитель, после смены канала - обслуживает. */
    @GameTest(template = ARENA, batch = "channel", timeoutTicks = 260)
    public static void radarChannelsAreSeparate(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Player p = player(h);
        RadarDomeBlockEntity d = dome(h, new BlockPos(8, 1, 38));
        d.setChannel(DyeColor.RED, p);
        movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 26.5), 0.12, 14);
        h.runAtTickTime(60, () -> {
            for (MountState m : be.mountStates()) if (m.status != Status.NO_RADAR) h.fail("white FCS used a red radar: " + m.status);
            be.setChannel(DyeColor.RED, p);
        });
        h.runAtTickTime(140, () -> {
            if (be.linkedRadars().isEmpty()) h.fail("red FCS does not see the red radar");
            for (MountState m : be.mountStates()) if (m.status == Status.NO_RADAR) h.fail("still NO_RADAR after channel change");
            h.succeed();
        });
    }
}
