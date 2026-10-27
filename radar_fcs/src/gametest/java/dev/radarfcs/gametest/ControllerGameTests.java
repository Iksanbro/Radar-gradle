package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.motor;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AimControllerBlockEntity;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.core.Aim;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Контроллеры наводки: без них станок не наводится; поставленные игроком - поворачивают его со скоростью своего вала. */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class ControllerGameTests {

    private static final String ARENA = "arena";

    @BeforeBatch(batch = "ctrl") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "ctrl") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }

    /**
     * Станок без контроллеров: статус «нет контроллера наводки», ствол не поворачивается. Игрок ставит контроллеры
     * (горизонтальный - на мотор под станком, вертикальный - кликом по боку станка), на валах 64 об/мин:
     * станок поворачивается не быстрее 64 x 0.0375 = 2.4 град/тик и попадает.
     */
    @GameTest(template = ARENA, batch = "ctrl", timeoutTicks = 500)
    public static void controllersPlacedByPlayerDriveTheMount(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32);
        autocannon(h, mount, Direction.EAST, 4, 64);
        // убрать контроллеры и моторы, которые ставит помощник
        h.setBlock(mount.below(), Blocks.SMOOTH_STONE.defaultBlockState());
        h.setBlock(mount.below(2), Blocks.AIR.defaultBlockState());
        h.setBlock(mount.south(), Blocks.AIR.defaultBlockState());
        h.setBlock(mount.south(2), Blocks.AIR.defaultBlockState());
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(20.5, 1, 50.5), 0.12, 6);
        AtomicInteger hits = countHits(h, husk);
        logEvery(h, be, "ctrl", 20);

        double[] yaw = {Double.NaN, 0, Double.NaN};             // прошлый yaw, наибольший шаг, yaw до контроллеров
        AtomicBoolean placed = new AtomicBoolean();
        h.onEachTick(() -> {
            if (!(h.getLevel().getBlockEntity(h.absolutePos(mount)) instanceof net.minecraft.world.level.block.entity.BlockEntity mbe)) return;
            CbcMount d = CbcMount.of(mbe);
            if (d == null) return;
            double y = d.yaw();
            if (placed.get() && !Double.isNaN(yaw[0])) yaw[1] = Math.max(yaw[1], Math.abs(Aim.wrap180(y - yaw[0])));
            yaw[0] = y;
        });
        h.runAtTickTime(60, () -> {
            MountState m = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            if (m == null) h.fail("mount not linked");
            if (m.status != Status.NO_CONTROLLER) h.fail("expected NO_CONTROLLER without controllers, got " + m.status);
            if (hits.get() > 0) h.fail("fired without controllers");
            yaw[2] = yaw[0];
            // игрок ставит контроллеры
            Player p = h.makeMockPlayer(GameType.CREATIVE);
            motor(h, mount.below(2), Direction.UP, 64);
            h.setBlock(mount.below(), Blocks.AIR.defaultBlockState());
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FcsRegistry.YAW_CONTROLLER_ITEM.get()));
            LinkGameTests.use(h, p, mount.below(2), Direction.UP);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FcsRegistry.PITCH_CONTROLLER_ITEM.get()));
            LinkGameTests.use(h, p, mount, Direction.SOUTH);
            FcsGameTests.CREATED.add(h.absolutePos(mount.below()));
            FcsGameTests.CREATED.add(h.absolutePos(mount.south()));
            var ys = h.getBlockState(mount.below()); var ps = h.getBlockState(mount.south());
            if (!ys.is(FcsRegistry.YAW_CONTROLLER.get()) || ys.getValue(DirectionalKineticBlock.FACING) != Direction.UP) h.fail("yaw controller placed wrong: " + ys);
            if (!ps.is(FcsRegistry.PITCH_CONTROLLER.get()) || ps.getValue(DirectionalKineticBlock.FACING) != Direction.NORTH) h.fail("pitch controller placed wrong: " + ps);
            motor(h, mount.south(2), Direction.NORTH, 64);
            placed.set(true);
        });
        h.succeedWhen(() -> {
            if (!placed.get()) throw new GameTestAssertException("controllers not placed yet");
            AimControllerBlockEntity yc = AimControllerBlockEntity.find(h.getLevel(), h.absolutePos(mount), false);
            AimControllerBlockEntity pc = AimControllerBlockEntity.find(h.getLevel(), h.absolutePos(mount), true);
            if (yc == null || pc == null) throw new GameTestAssertException("controllers not found by the mount");
            if (Math.abs(yc.degPerTick() - 2.4) > 0.01) throw new GameTestAssertException("controller rate " + yc.degPerTick());
            if (yaw[1] > 2.45) h.fail("mount turned " + yaw[1] + " deg in one tick, faster than the controller allows (2.4)");
            if (hits.get() == 0) throw new GameTestAssertException("target not hit yet");
            double turned = Math.abs(Aim.wrap180(yaw[0] - yaw[2]));
            FcsMod.LOG.info("[radar_fcs test] ctrl: turned {} deg, max step {} deg/tick, hits {}", turned, yaw[1], hits.get());
            if (turned < 20) h.fail("mount barely turned: " + turned);
        });
    }
}
