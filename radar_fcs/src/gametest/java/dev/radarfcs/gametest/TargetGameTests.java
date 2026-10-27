package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;
import static dev.radarfcs.gametest.SettingsGameTests.configure;
import static dev.radarfcs.gametest.SettingsGameTests.roundTicks;
import static dev.radarfcs.gametest.SettingsGameTests.tough;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Цели вычислителя: белый и чёрный списки (ID, тег, имя игрока), мирные мобы только через белый список, прочие
 * сущности (лодка), игрок в режиме выживания; кнопки «Собрать» и «Разобрать». У каждого теста своя партия.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class TargetGameTests {

    private static final String ARENA = "arena";
    private static final BlockPos MOUNT = new BlockPos(10, 1, 32);
    private static final BlockPos FCS = new BlockPos(6, 1, 28);
    /** Имя манекена GameTestHelper.makeMockPlayer. */
    static final String MOCK_NAME = "test-mock-player";

    @BeforeBatch(batch = "tgt_white") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "tgt_white") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "tgt_black") public static void b2(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "tgt_black") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "tgt_boat") public static void b3(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "tgt_boat") public static void a3(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "tgt_player") public static void b4(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "tgt_player") public static void a4(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "tgt_assembly") public static void b5(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "tgt_assembly") public static void a5(ServerLevel l) { FcsGameTests.cleanup(l); }

    /**
     * Игрок-манекен в режиме выживания, добавленный в мир как сущность (у ServerPlayer-манекена GameTestHelper всегда
     * творческий режим - он не цель, а настоящее подключение манекена ломают пакеты модов).
     */
    static Player survivalPlayer(GameTestHelper h, Vec3 pos) {
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        p.setPos(pos);
        h.getLevel().addFreshEntity(p);
        return p;
    }

    static void removeMockPlayers(ServerLevel level) {
        List<net.minecraft.world.entity.Entity> doomed = new java.util.ArrayList<>();
        for (var e : level.getAllEntities()) if (e instanceof Player p && !(p instanceof ServerPlayer)) doomed.add(e);
        doomed.forEach(net.minecraft.world.entity.Entity::discard);
    }

    // ------------------------------------------------------------------- тесты

    /** Мирный моб (корова) по умолчанию не цель («только враждебные»); в белом списке - цель, по ней огонь. */
    @GameTest(template = ARENA, batch = "tgt_white", timeoutTicks = 500)
    public static void whitelistTakesPassiveMob(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        Mob cow = tough(movingTarget(h, EntityType.COW, new Vec3(40.5, 1, 24.5), 0.15, 16));
        AtomicInteger hits = countHits(h, cow);
        List<Long> rounds = roundTicks(h, MOUNT);
        logEvery(h, be, "tgt_white", 40);
        h.runAtTickTime(120, () -> {
            if (!rounds.isEmpty()) h.fail("fired at a passive mob without the whitelist: " + rounds);
            if (be.sensor().all().isEmpty()) h.fail("cow was not seen at all");
            if (!be.sensor().active().isEmpty()) h.fail("passive mob is active without the whitelist");
            configure(be, s -> s.whitelist.add("minecraft:cow"));
        });
        h.succeedWhen(() -> {
            if (h.getTick() <= 120 || hits.get() == 0) throw new GameTestAssertException("whitelisted cow not hit yet");
            FcsMod.LOG.info("[radar_fcs test] tgt_white: whitelisted passive mob hit {} times", hits.get());
        });
    }

    /** Чёрный список по тегу (#minecraft:undead): враждебный моб не цель; запись убрали - огонь. */
    @GameTest(template = ARENA, batch = "tgt_black", timeoutTicks = 500)
    public static void blacklistTagExcludesHostile(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> s.blacklist.add("#minecraft:undead"));
        Mob husk = tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16));
        AtomicInteger hits = countHits(h, husk);
        List<Long> rounds = roundTicks(h, MOUNT);
        logEvery(h, be, "tgt_black", 40);
        h.runAtTickTime(120, () -> {
            if (!rounds.isEmpty()) h.fail("fired at a blacklisted target: " + rounds);
            if (!be.sensor().active().isEmpty()) h.fail("blacklisted target is active");
            configure(be, s -> s.blacklist.clear());
        });
        h.succeedWhen(() -> {
            if (h.getTick() <= 120 || hits.get() == 0) throw new GameTestAssertException("target not hit after clearing the blacklist");
        });
    }

    /** Прочие сущности: лодка (не моб) - цель типа «сущности», вычислитель стреляет и разбивает её. */
    @GameTest(template = ARENA, batch = "tgt_boat", timeoutTicks = 500)
    public static void boatIsEngaged(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        Boat boat = new Boat(EntityType.BOAT, h.getLevel());
        Vec3 start = h.absoluteVec(new Vec3(40.5, 1, 24.5));
        boat.setPos(start);
        h.getLevel().addFreshEntity(boat);
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            if (!boat.isAlive()) return;
            int t = tick.getAndIncrement();
            double period = 16 / 0.15, phase = t % (2 * period);
            double off = phase < period ? phase * 0.15 : (2 * period - phase) * 0.15;
            boat.setPos(start.x, start.y, start.z + off);
            boat.setDeltaMovement(Vec3.ZERO);
        });
        String id = boat.getUUID().toString();
        AtomicBoolean aimed = new AtomicBoolean();
        h.onEachTick(() -> {
            for (MountState m : be.mountStates()) if (id.equals(m.targetId) && m.status == Status.FIRE) aimed.set(true);
        });
        List<Long> rounds = roundTicks(h, MOUNT);
        logEvery(h, be, "tgt_boat", 40);
        h.succeedWhen(() -> {
            if (!aimed.get()) throw new GameTestAssertException("boat not engaged yet");
            if (boat.isAlive()) throw new GameTestAssertException("boat not destroyed yet (" + rounds.size() + " rounds)");
            FcsMod.LOG.info("[radar_fcs test] tgt_boat: boat destroyed after {} rounds", rounds.size());
        });
    }

    /**
     * Игрок в режиме выживания: тип «игроки» выключен (по умолчанию), но имя игрока в белом списке - вычислитель
     * берёт его в цель и открывает огонь. Игрок в творческом режиме (манекен GameTestHelper) - не цель.
     */
    @GameTest(template = ARENA, batch = "tgt_player", timeoutTicks = 400)
    public static void whitelistedPlayerIsEngaged(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> s.whitelist.add(MOCK_NAME.toUpperCase(java.util.Locale.ROOT)));   // регистр не важен
        Vec3 start = h.absoluteVec(new Vec3(40.5, 1, 24.5));
        Player target = survivalPlayer(h, start);
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            int t = tick.getAndIncrement();
            double period = 16 / 0.15, phase = t % (2 * period);
            double off = phase < period ? phase * 0.15 : (2 * period - phase) * 0.15;
            target.setPos(start.x, start.y, start.z + off);
            target.setDeltaMovement(Vec3.ZERO);
        });
        String id = target.getUUID().toString();
        AtomicInteger firing = new AtomicInteger();
        h.onEachTick(() -> {
            for (MountState m : be.mountStates()) if (id.equals(m.targetId) && m.status == Status.FIRE) firing.incrementAndGet();
        });
        List<Long> rounds = roundTicks(h, MOUNT);
        logEvery(h, be, "tgt_player", 40);
        h.succeedWhen(() -> {
            if (firing.get() < 3 || rounds.size() < 3) {
                throw new GameTestAssertException("not firing at the whitelisted player yet: " + firing.get() + " ticks, " + rounds.size() + " rounds");
            }
            FcsMod.LOG.info("[radar_fcs test] tgt_player: firing at the whitelisted survival player, {} rounds", rounds.size());
        });
    }

    /**
     * Кнопки экрана вычислителя: «Разобрать» разбирает собранную пушку, и вычислитель не собирает её снова (состояние
     * сохраняется в блоке); «Собрать» собирает сразу.
     */
    @GameTest(template = ARENA, batch = "tgt_assembly", timeoutTicks = 300)
    public static void assembleAndDisassembleButtons(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 2, 8);
        FireControlBlockEntity be = fcs(h, FCS);
        h.runAtTickTime(60, () -> {
            CbcMount d = mount(h, be);
            if (d == null || !d.isAssembled()) h.fail("cannon was not auto-assembled");
            be.disassembleAll(null);
            if (d.isAssembled()) h.fail("cannon still assembled after Disassemble");
        });
        h.runAtTickTime(160, () -> {
            CbcMount d = mount(h, be);
            if (d == null) h.fail("mount lost");
            if (d.isAssembled()) h.fail("computer re-assembled a cannon disassembled with the button");
            if (be.mountStates().get(0).status != Status.DISASSEMBLED) h.fail("status " + be.mountStates().get(0).status);
            CompoundTag tag = be.saveWithoutMetadata(h.getLevel().registryAccess());
            if (!tag.contains("Assemble") || tag.getBoolean("Assemble")) h.fail("disassembled state not saved: " + tag.get("Assemble"));
            be.assembleAll(null);
            if (!d.isAssembled()) h.fail("Assemble did not assemble the cannon: " + be.mountStates().get(0).note);
        });
        h.runAtTickTime(200, () -> {
            CbcMount d = mount(h, be);
            if (d == null || !d.isAssembled()) h.fail("cannon not assembled after Assemble");
            Status st = be.mountStates().get(0).status;
            if (st == Status.DISASSEMBLED || st == Status.NOT_ASSEMBLED) h.fail("status after Assemble: " + st);
            h.succeed();
        });
    }

    private static CbcMount mount(GameTestHelper h, FireControlBlockEntity be) {
        return be.mountStates().isEmpty() ? null : be.mountStates().get(0).mount;
    }
}
