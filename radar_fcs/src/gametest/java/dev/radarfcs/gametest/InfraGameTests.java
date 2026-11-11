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
    @net.minecraft.gametest.framework.BeforeBatch(batch = "infra_rpl") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @net.minecraft.gametest.framework.AfterBatch(batch = "infra_rpl") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }

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
    @net.minecraft.gametest.framework.BeforeBatch(batch = "infra_chunks") public static void b2(ServerLevel l) { FcsGameTests.direct(l); }
    @net.minecraft.gametest.framework.AfterBatch(batch = "infra_chunks") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }

    /**
     * Чанк станка, который уже выгружается (тикеты сняты, блоки ещё на месте), вычислитель не трогает. Раньше проверка
     * «станок на месте» читала блок из мира - это возвращало чанк к жизни вместе с соседями: при остановке сервера
     * выгрузка мира зацикливалась («Сохранение мира» без конца: вычислитель, выгружаясь, отпускал станок из соседнего
     * чанка), а в игре чанк дальнего станка не выгружался, пока тикает вычислитель.
     */
    @GameTest(template = "arena", batch = "infra_chunks", timeoutTicks = 200)
    public static void mountInUnloadingChunkIsNotRevived(GameTestHelper h) {
        floor(h);
        ServerLevel l = h.getLevel();
        var chunks = l.getChunkSource();
        FireControlBlockEntity fcsBe = FcsGameTests.fcsBare(h, new BlockPos(6, 1, 28));
        // чанк в стороне от площадок: держим его сами и сами отпускаем
        ChunkPos cp = new ChunkPos(h.absolutePos(new BlockPos(6, 1, 28)).offset(3000, 0, -3000));
        BlockPos pos = new BlockPos(cp.getMinBlockX() + 8, 100, cp.getMinBlockZ() + 8);
        l.setChunkForced(cp.x, cp.z, true);
        AtomicInteger phase = new AtomicInteger(), since = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<dev.radarfcs.compat.CbcMount> mount = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<net.minecraft.world.level.block.entity.BlockEntity> blockEntity = new java.util.concurrent.atomic.AtomicReference<>();
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            switch (phase.get()) {
                case 0 -> {
                    if (chunks.getChunkNow(cp.x, cp.z) == null) return;       // ещё грузится
                    l.setBlock(pos, FcsGameTests.block("createbigcannons:cannon_mount").defaultBlockState(), 3);
                    var be = l.getBlockEntity(pos);
                    dev.radarfcs.compat.CbcMount m = dev.radarfcs.compat.CbcMount.of(be);
                    if (m == null || !m.isValid()) throw new net.minecraft.gametest.framework.GameTestAssertException("no valid mount in the far chunk");
                    mount.set(m);
                    blockEntity.set(be);
                    since.set(t);
                    phase.set(1);
                }
                case 1 -> {
                    if (t - since.get() < 4) return;                           // тикеты от обращений к блоку истекли
                    phase.set(2);
                    l.setChunkForced(cp.x, cp.z, false);
                    chunks.pollTask();                                         // уровни чанков пересчитаны, выгрузки ещё не было
                    var be = blockEntity.get();
                    if (l.isLoaded(pos) || be.isRemoved()) {
                        throw new net.minecraft.gametest.framework.GameTestAssertException("setup: the far chunk must be dropped but not unloaded yet: loaded "
                                + l.isLoaded(pos) + ", block entity removed " + be.isRemoved());
                    }
                    dev.radarfcs.compat.CbcMount m = mount.get();
                    boolean valid = m.isValid();
                    // вычислитель, выгружаясь сам, отпускает свои станки
                    dev.radarfcs.fcs.MountState ms = new dev.radarfcs.fcs.MountState(pos);
                    ms.mount = m;
                    ms.locked = true;
                    ms.triggerOn = true;
                    dev.radarfcs.fcs.FcsControl.release(l, fcsBe, ms);
                    chunks.pollTask();
                    boolean revived = l.isLoaded(pos) || chunks.hasChunk(cp.x, cp.z);
                    if (valid) throw new net.minecraft.gametest.framework.GameTestAssertException("a mount in an unloading chunk is reported valid");
                    if (revived) throw new net.minecraft.gametest.framework.GameTestAssertException("releasing a mount revived its unloading chunk");
                    FcsMod.LOG.info("[radar_fcs test] unloading_chunk: mount at {} not valid, chunk {} stays dropped", pos, cp);
                    h.succeed();
                }
                default -> { }
            }
        });
    }

    /**
     * Спуск, оставшийся зажатым с прошлой загрузки мира (мир закрыли во время стрельбы: темп огня CBC хранит в самой
     * пушке, а станок, выгружаясь вместе с вычислителем, уже не отпускается): вычислитель, взяв станок заново, считает
     * спуск своим и отпускает его, когда стрелять не по чему. «Загрузка заново» - состояния пушек вычислителя сброшены
     * (как у только что загруженного), сигнал огня на станке выставлен мимо вычислителя; блоки рядом со станком не
     * меняются (обновление соседей сняло бы сигнал само).
     */
    @GameTest(template = "arena", batch = "infra_chunks", timeoutTicks = 300)
    public static void staleTriggerReleasedOnClaim(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32), fcsPos = new BlockPos(6, 1, 28);
        autocannon(h, mount, Direction.EAST, 4, 0);                           // без патронов: важен только сигнал
        FireControlBlockEntity be = fcs(h, fcsPos);
        ServerLevel l = h.getLevel();
        BlockPos abs = h.absolutePos(mount);
        AtomicInteger reloaded = new AtomicInteger(-1);
        h.onEachTick(() -> {
            int t = (int) h.getTick();
            dev.radarfcs.compat.CbcMount m = dev.radarfcs.compat.CbcMount.of(l.getBlockEntity(abs));
            if (m == null) throw new net.minecraft.gametest.framework.GameTestAssertException("no mount");
            if (reloaded.get() < 0) {
                // вычислитель взял станок и собрал пушку (целей нет - спуск отпущен)
                boolean ready = !be.mountStates().isEmpty() && be.mountStates().get(0).claimed && m.isAssembled() && !m.firePowered();
                if (ready && t >= 30) {
                    be.releaseAll();
                    m.setFire(true, 15);
                    if (!m.firePowered()) throw new net.minecraft.gametest.framework.GameTestAssertException("setup: the mount did not take the fire signal");
                    be.requestDiscover();
                    reloaded.set(t);
                } else if (t == 120) {
                    throw new net.minecraft.gametest.framework.GameTestAssertException("setup: the computer did not take the mount: states " + be.mountStates().size());
                }
                return;
            }
            if (t > reloaded.get() + 1 && !m.firePowered()) {
                FcsMod.LOG.info("[radar_fcs test] stale_trigger: released {} ticks after the computer took the mount again", t - reloaded.get());
                h.succeed();
            }
            if (t == reloaded.get() + 120) throw new net.minecraft.gametest.framework.GameTestAssertException("the stale fire signal is still on");
        });
    }
}
