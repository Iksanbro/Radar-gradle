package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Обвязка игровых тестов. RPL (библиотека снарядов CBC) снимает принудительную загрузку с чанков, которые сервер тестов
 * загрузил после неё (снаряд прошлого теста пролетал над местом новой площадки): снаряды в таком чанке стоят, попаданий
 * нет. Тест повторяет это нарочно; сторож `DevAutostop.keepForcedTickets` должен вернуть тикет - пушка попадает.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class InfraGameTests {
    @GameTest(template = "arena", batch = "infra_rpl", timeoutTicks = 400)
    public static void forcedChunkSurvivesRpl(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32);
        ServerLevel l = h.getLevel();
        // чанки линии огня: как если бы над ними раньше пролетел снаряд прошлого теста (RPL загрузил их первым)
        BlockPos a = h.absolutePos(new BlockPos(12, 1, 32)), b = h.absolutePos(new BlockPos(44, 1, 32));
        java.util.List<ChunkPos> line = new java.util.ArrayList<>();
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) line.add(new ChunkPos(cx, cz));
        }
        for (ChunkPos c : line) {
            l.setChunkForced(c.x, c.z, false);
            try {
                Class.forName("rbasamoyai.ritchiesprojectilelib.RitchiesProjectileLib")
                        .getMethod("queueForceLoad", ServerLevel.class, int.class, int.class).invoke(null, l, c.x, c.z);
            } catch (ReflectiveOperationException e) {
                h.fail("RPL: " + e);
            }
        }
        BlockPos probe = h.absolutePos(new BlockPos(30, 2, 32));
        autocannon(h, mount, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        SettingsGameTests.configure(be, s -> s.dropStatic = false);
        Mob husk = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 32.5), 0, 0));
        AtomicInteger[] holder = {countHits(h, husk)};
        // сервер тестов грузит чанки площадки принудительно - уже после RPL
        h.runAtTickTime(3, () -> {
            for (ChunkPos c : line) l.setChunkForced(c.x, c.z, true);
        });
        h.succeedWhen(() -> {
            if (h.getTick() < 20) throw new net.minecraft.gametest.framework.GameTestAssertException("waiting");
            if (!l.isPositionEntityTicking(probe)) throw new net.minecraft.gametest.framework.GameTestAssertException("line of fire not ticking");
            if (holder[0].get() < 3) throw new net.minecraft.gametest.framework.GameTestAssertException("hits " + holder[0].get());
            FcsMod.LOG.info("[radar_fcs test] infra_rpl: chunks released by RPL restored, hits {}", holder[0].get());
        });
    }
}
