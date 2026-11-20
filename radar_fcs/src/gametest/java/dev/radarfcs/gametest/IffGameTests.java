package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.bigCannon;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;
import static dev.radarfcs.gametest.SettingsGameTests.configure;
import static dev.radarfcs.gametest.SettingsGameTests.tough;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.IffTransponderBlockEntity;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.SettingsNbt;
import dev.radarfcs.iff.TransponderMode;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Свой-чужой по кодам: белый и чёрный списки кодов вычислителя, код, режим выделения и радиус транспондера,
 * транспондер на корабле Sable, доступ к кодам только владельцу. Цели - коровы, взятые в цели белым списком целей
 * (мирные мобы иначе не цели), - так видно, что их бережёт именно транспондер.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class IffGameTests {

    private static final String ARENA = "arena";
    private static final BlockPos MOUNT = new BlockPos(10, 1, 32);
    private static final BlockPos FCS = new BlockPos(6, 1, 28);

    @BeforeBatch(batch = "iff_white") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_white") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_black") public static void b2(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_black") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_radius") public static void b3(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_radius") public static void a3(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_sublevel_ground") public static void b4(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_sublevel_ground") public static void a4(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_access") public static void b5(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_access") public static void a5(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_ship_foreign") public static void b6(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_ship_foreign") public static void a6(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_ship_own") public static void b7(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_ship_own") public static void a7(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_combined_ground") public static void b8(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_combined_ground") public static void a8(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_ship_rider") public static void b9(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_ship_rider") public static void a9(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_ship_escort") public static void b10(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_ship_escort") public static void a10(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_ship_escort_out") public static void b11(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_ship_escort_out") public static void a11(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_inherit") public static void b12(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_inherit") public static void a12(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_inherit_white") public static void b13(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_inherit_white") public static void a13(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_inherit_black") public static void b14(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_inherit_black") public static void a14(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_ship_name") public static void b15(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_ship_name") public static void a15(ServerLevel l) { FcsGameTests.cleanup(l); }
    @BeforeBatch(batch = "iff_monitor") public static void b16(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "iff_monitor") public static void a16(ServerLevel l) { FcsGameTests.cleanup(l); }

    /** Транспондер с кодом, режимом и радиусом (как сохранил бы игрок на экране транспондера). */
    static IffTransponderBlockEntity transponder(GameTestHelper h, BlockPos rel, String code, TransponderMode mode, int radius) {
        h.setBlock(rel, FcsRegistry.IFF_TRANSPONDER.get().defaultBlockState());
        FcsGameTests.CREATED.add(h.absolutePos(rel));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(rel)) instanceof IffTransponderBlockEntity be)) {
            throw new GameTestAssertException("no transponder at " + rel);
        }
        if (!be.apply(code, mode, radius, null)) throw new GameTestAssertException("transponder settings rejected");
        return be;
    }

    /** Вычислитель стреляет по коровам (белый список целей) с правилом «свой-чужой» по кодам. */
    static FireControlBlockEntity cowHunter(GameTestHelper h, boolean whitelist, String... codes) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> {
            s.whitelist.add("minecraft:cow");
            s.iffWhitelist = whitelist;
            s.iffCodes.addAll(List.of(codes));
        });
        return be;
    }

    static boolean friendly(FireControlBlockEntity be, Mob m) {
        var t = be.sensor().get(m.getUUID().toString());
        return t != null && t.friendly;
    }

    /** Не стреляет ли вычислитель по мобу (каждый тик). */
    static void neverShot(GameTestHelper h, FireControlBlockEntity be, Mob m, AtomicInteger hits, String what) {
        String id = m.getUUID().toString();
        h.onEachTick(() -> {
            if (hits.get() > 0) throw new GameTestAssertException(what + " was shot");
            for (MountState ms : be.mountStates()) if (id.equals(ms.targetId)) throw new GameTestAssertException(what + " was picked as a target");
        });
    }

    // ------------------------------------------------------------------- тесты

    /**
     * Белый список кодов: транспондер с кодом 1234 из списка бережёт корову; список сменили на 9999 - тот же
     * транспондер чужой, корова - цель.
     */
    @GameTest(template = ARENA, batch = "iff_white", timeoutTicks = 500)
    public static void whitelistCodeIdentifies(GameTestHelper h) {
        FireControlBlockEntity be = cowHunter(h, true, "1234");
        transponder(h, new BlockPos(48, 1, 26), "1234", TransponderMode.RADIUS, 12);
        Mob cow = tough(movingTarget(h, EntityType.COW, new Vec3(46.5, 1, 22.5), 0.1, 6));
        AtomicInteger hits = countHits(h, cow);
        logEvery(h, be, "iff_white", 40);
        h.runAtTickTime(120, () -> {
            if (hits.get() > 0) h.fail("cow protected by a whitelisted code was shot");
            if (!friendly(be, cow)) h.fail("cow near a transponder with a whitelisted code is not friendly");
            configure(be, s -> { s.iffCodes.clear(); s.iffCodes.add("9999"); });
        });
        h.succeedWhen(() -> {
            if (h.getTick() <= 120 || hits.get() == 0) throw new GameTestAssertException("cow not hit after the code left the whitelist");
            FcsMod.LOG.info("[radar_fcs test] iff_white: protected while code 1234 was listed, hit {} times after", hits.get());
        });
    }

    /** Чёрный список кодов: транспондер 666 из списка - чужой (корова у него - цель), транспондер 777 - свой. */
    @GameTest(template = ARENA, batch = "iff_black", timeoutTicks = 500)
    public static void blacklistCodeExposes(GameTestHelper h) {
        FireControlBlockEntity be = cowHunter(h, false, "666");
        transponder(h, new BlockPos(48, 1, 22), "666", TransponderMode.RADIUS, 6);
        transponder(h, new BlockPos(48, 1, 44), "777", TransponderMode.RADIUS, 6);
        Mob exposed = tough(movingTarget(h, EntityType.COW, new Vec3(46.5, 1, 20.5), 0.1, 4));
        Mob safe = tough(movingTarget(h, EntityType.COW, new Vec3(46.5, 1, 42.5), 0.1, 4));
        AtomicInteger hitsExposed = countHits(h, exposed), hitsSafe = countHits(h, safe);
        neverShot(h, be, safe, hitsSafe, "cow near the 777 transponder");
        logEvery(h, be, "iff_black", 40);
        h.succeedWhen(() -> {
            if (!friendly(be, safe)) throw new GameTestAssertException("cow near the 777 transponder is not friendly");
            if (friendly(be, exposed)) throw new GameTestAssertException("cow near the blacklisted 666 transponder is friendly");
            if (hitsExposed.get() == 0) throw new GameTestAssertException("cow near the blacklisted transponder not hit yet");
            FcsMod.LOG.info("[radar_fcs test] iff_black: blacklisted code exposed its cow ({} hits), 777 protected", hitsExposed.get());
        });
    }

    /** Радиус транспондера настраивается: 12 блоков - корова в 7 блоках своя; 3 блока - уже нет, огонь. */
    @GameTest(template = ARENA, batch = "iff_radius", timeoutTicks = 500)
    public static void transponderRadiusIsConfigurable(GameTestHelper h) {
        FireControlBlockEntity be = cowHunter(h, false);
        IffTransponderBlockEntity t = transponder(h, new BlockPos(48, 1, 30), "", TransponderMode.RADIUS, 12);
        Mob cow = tough(movingTarget(h, EntityType.COW, new Vec3(46.5, 1, 36.5), 0.1, 3));
        AtomicInteger hits = countHits(h, cow);
        logEvery(h, be, "iff_radius", 40);
        h.runAtTickTime(120, () -> {
            if (hits.get() > 0 || !friendly(be, cow)) h.fail("cow inside the 12 block radius is not protected");
            t.apply("", TransponderMode.RADIUS, 3, null);
        });
        h.succeedWhen(() -> {
            if (h.getTick() <= 120 || hits.get() == 0) throw new GameTestAssertException("cow outside the 3 block radius not hit yet");
        });
    }

    /** Режим «сублевел» у транспондера не на корабле ничего вокруг не защищает: корова рядом - цель. */
    @GameTest(template = ARENA, batch = "iff_sublevel_ground", timeoutTicks = 400)
    public static void sublevelModeOnGroundProtectsNothing(GameTestHelper h) {
        FireControlBlockEntity be = cowHunter(h, false);
        transponder(h, new BlockPos(48, 1, 30), "", TransponderMode.SUBLEVEL, 12);
        Mob cow = tough(movingTarget(h, EntityType.COW, new Vec3(46.5, 1, 26.5), 0.1, 4));
        AtomicInteger hits = countHits(h, cow);
        logEvery(h, be, "iff_sublevel_ground", 40);
        h.succeedWhen(() -> {
            if (hits.get() == 0) throw new GameTestAssertException("cow next to a ground transponder in sublevel mode not hit yet");
        });
    }

    /**
     * Коды видит и меняет только владелец: в обновлении блока для всех клиентов кодов нет, чужой игрок не меняет
     * ни коды вычислителя, ни настройки транспондера; владелец - меняет; код чистится (только цифры), радиус ограничен.
     */
    @GameTest(template = ARENA, batch = "iff_access", timeoutTicks = 100)
    public static void codesOnlyForOwner(GameTestHelper h) {
        floor(h);
        FireControlBlockEntity be = FcsGameTests.fcsBare(h, FCS);
        Player owner = h.makeMockPlayer(GameType.SURVIVAL), stranger = h.makeMockPlayer(GameType.SURVIVAL);
        be.setOwner(owner);
        EngageSettings s = be.settings().copy();
        s.iffWhitelist = true;
        s.iffCodes.add("2468");
        be.applySettings(s, owner);
        CompoundTag update = be.getUpdateTag(h.getLevel().registryAccess());
        EngageSettings seen = SettingsNbt.read(update.getCompound("Settings"));
        if (!seen.iffCodes.isEmpty()) h.fail("IFF codes leak to clients in the block update: " + seen.iffCodes);
        if (!seen.iffWhitelist) h.fail("IFF mode missing in the block update");
        EngageSettings hack = be.settings().copy();
        hack.iffWhitelist = false;
        hack.iffCodes.clear();
        hack.iffCodes.add("1111");
        be.applySettings(hack, stranger);
        if (!be.settings().iffWhitelist || !be.settings().iffCodes.equals(List.of("2468"))) {
            h.fail("a stranger changed the IFF codes: " + be.settings().iffCodes);
        }
        IffTransponderBlockEntity t = transponder(h, new BlockPos(12, 1, 12), "", TransponderMode.RADIUS, 12);
        t.setOwner(owner);
        if (!t.apply("2468", TransponderMode.RADIUS, 20, owner) || !t.code().equals("2468")) h.fail("owner could not set the code");
        if (t.apply("1111", TransponderMode.SUBLEVEL, 5, stranger) || !t.code().equals("2468")) h.fail("a stranger changed the transponder");
        if (t.canEdit(stranger) || !t.canEdit(owner)) h.fail("wrong transponder access");
        t.apply("12a4", TransponderMode.RADIUS, 100000, owner);
        if (!t.code().isEmpty()) h.fail("code with letters accepted: " + t.code());
        if (t.radius() != IffTransponderBlockEntity.maxRadius()) h.fail("radius not clamped: " + t.radius());
        CompoundTag tu = t.getUpdateTag(h.getLevel().registryAccess());
        if (tu.contains("Code")) h.fail("transponder code leaks to clients");
        h.succeed();
    }

    /** Корабль Sable с транспондером, чей код не в белом списке вычислителя, - чужой: огонь и попадание. */
    @GameTest(template = ARENA, batch = "iff_ship_foreign", timeoutTicks = 600)
    public static void shipWithForeignCodeIsEngaged(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        bigCannon(h, MOUNT, Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> { s.iffWhitelist = true; s.iffCodes.add("4242"); });
        Object sub = SableTestSupport.ship(h, new BlockPos(46, 9, 22), 3, new Vec3(0, 0, 0.12), 14, "1111");
        logEvery(h, be, "iff_ship_foreign", 20);
        AtomicInteger hits = SableTestSupport.watchShipHits(h, sub, "iff_ship_foreign");
        h.succeedWhen(() -> {
            if (hits.get() == 0) throw new GameTestAssertException("ship with a foreign code not hit yet");
            FcsMod.LOG.info("[radar_fcs test] iff_ship_foreign: ship with code 1111 engaged (whitelist 4242)");
        });
    }

    /** Корабль Sable с транспондером из белого списка (режим «сублевел») - свой, огня нет. */
    @GameTest(template = ARENA, batch = "iff_ship_own", timeoutTicks = 260)
    public static void shipWithOwnCodeIsFriendly(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        bigCannon(h, MOUNT, Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> { s.iffWhitelist = true; s.iffCodes.add("4242"); });
        Object sub = SableTestSupport.ship(h, new BlockPos(46, 9, 22), 3, new Vec3(0, 0, 0.12), 14, "4242");
        logEvery(h, be, "iff_ship_own", 40);
        AtomicInteger hits = SableTestSupport.watchShipHits(h, sub, "iff_ship_own");
        h.runAtTickTime(220, () -> {
            if (hits.get() > 0) h.fail("ship with a whitelisted code was shot");
            boolean seen = false;
            for (var t : be.sensor().all()) if (t.kind == dev.radarfcs.core.Track.Kind.SHIP) seen = true;
            if (!seen) h.fail("ship was not detected at all");
            if (!be.sensor().active().isEmpty()) h.fail("ship with a whitelisted code is active");
            h.succeed();
        });
    }

    /** Режим «сублевел и расстояние» не на корабле - шар заданного радиуса: корова в 5 блоках своя. */
    @GameTest(template = ARENA, batch = "iff_combined_ground", timeoutTicks = 300)
    public static void combinedModeOnGroundActsAsRadius(GameTestHelper h) {
        FireControlBlockEntity be = cowHunter(h, false);
        transponder(h, new BlockPos(48, 1, 30), "", TransponderMode.SUBLEVEL_AND_DISTANCE, 8);
        Mob cow = tough(movingTarget(h, EntityType.COW, new Vec3(46.5, 1, 26.5), 0.1, 4));
        AtomicInteger hits = countHits(h, cow);
        neverShot(h, be, cow, hits, "cow within the distance of a ground transponder");
        logEvery(h, be, "iff_combined_ground", 40);
        h.runAtTickTime(200, () -> {
            if (!friendly(be, cow)) h.fail("cow within the distance is not friendly");
            h.succeed();
        });
    }

    /** Враждебный моб, который держится рядом с кораблём (на dx от его границы со стороны пушки, движется вместе с ним). */
    static Mob escort(GameTestHelper h, Object sub, double dx) {
        Mob m = h.spawn(EntityType.HUSK, new Vec3(40.5, 9, 22.5));
        m.setNoAi(true);
        m.setPersistenceRequired();
        tough(m);
        h.onEachTick(() -> {
            var b = SableTestSupport.box(sub);
            m.setPos(b.minX - dx, b.minY, (b.minZ + b.maxZ) / 2);           // со стороны пушки: линия огня не через корабль
            m.setDeltaMovement(Vec3.ZERO);
        });
        return m;
    }

    /** Режим «сублевел»: моб на палубе корабля (в его пределах) - свой, огня нет. */
    @GameTest(template = ARENA, batch = "iff_ship_rider", timeoutTicks = 300)
    public static void shipZoneProtectsRider(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        Object sub = SableTestSupport.ship(h, new BlockPos(46, 9, 22), 3, new Vec3(0, 0, 0.12), 14, "", TransponderMode.SUBLEVEL, 12);
        Mob rider = h.spawn(EntityType.HUSK, new Vec3(40.5, 13, 22.5));
        rider.setNoAi(true);
        tough(rider);
        h.onEachTick(() -> {
            var b = SableTestSupport.box(sub);
            rider.setPos((b.minX + b.maxX) / 2, b.maxY, (b.minZ + b.maxZ) / 2);
            rider.setDeltaMovement(Vec3.ZERO);
        });
        AtomicInteger hits = countHits(h, rider);
        neverShot(h, be, rider, hits, "hostile mob on the ship deck");
        logEvery(h, be, "iff_ship_rider", 40);
        h.runAtTickTime(240, () -> {
            if (!friendly(be, rider)) h.fail("hostile mob on the ship deck is not friendly");
            h.succeed();
        });
    }

    /** Режим «сублевел и расстояние» 8: моб в 4 блоках сбоку от корабля - свой. */
    @GameTest(template = ARENA, batch = "iff_ship_escort", timeoutTicks = 300)
    public static void shipCombinedZoneProtectsEscort(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        Object sub = SableTestSupport.ship(h, new BlockPos(46, 9, 22), 3, new Vec3(0, 0, 0.12), 14, "",
                TransponderMode.SUBLEVEL_AND_DISTANCE, 8);
        Mob esc = escort(h, sub, 4);
        AtomicInteger hits = countHits(h, esc);
        neverShot(h, be, esc, hits, "escort within the distance of the ship");
        logEvery(h, be, "iff_ship_escort", 40);
        h.runAtTickTime(240, () -> {
            if (!friendly(be, esc)) h.fail("escort within the distance of the ship is not friendly");
            h.succeed();
        });
    }

    /** Режим «сублевел» (без расстояния): тот же моб в 4 блоках сбоку от корабля - чужой, огонь и попадание. */
    @GameTest(template = ARENA, batch = "iff_ship_escort_out", timeoutTicks = 500)
    public static void shipZoneLeavesEscortOutside(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        Object sub = SableTestSupport.ship(h, new BlockPos(46, 9, 22), 3, new Vec3(0, 0, 0.12), 14, "", TransponderMode.SUBLEVEL, 12);
        Mob esc = escort(h, sub, 4);
        AtomicInteger hits = countHits(h, esc);
        logEvery(h, be, "iff_ship_escort_out", 40);
        h.succeedWhen(() -> {
            if (friendly(be, esc)) throw new GameTestAssertException("mob 4 blocks off the ship is friendly in sublevel mode");
            if (hits.get() == 0) throw new GameTestAssertException("mob next to the ship not hit yet");
        });
    }

    /**
     * Наследование защиты: в зоне одного транспондера корова (белый список целей) и враждебный моб. Корова наследует
     * защиту (своя, огня нет), враждебный моб - нет (цель, попадание).
     */
    static void inheritCase(GameTestHelper h, String batch, dev.radarfcs.iff.InheritPolicy policy) {
        FireControlBlockEntity be = cowHunter(h, false);
        IffTransponderBlockEntity t = transponder(h, new BlockPos(48, 1, 30), "", TransponderMode.RADIUS, 12);
        t.apply(new dev.radarfcs.iff.TransponderSettings("", "", TransponderMode.RADIUS, 12, policy), null);
        Mob cow = tough(movingTarget(h, EntityType.COW, new Vec3(46.5, 1, 22.5), 0.1, 4));
        Mob husk = tough(movingTarget(h, EntityType.HUSK, new Vec3(44.5, 1, 32.5), 0.1, 4));
        AtomicInteger cowHits = countHits(h, cow), huskHits = countHits(h, husk);
        neverShot(h, be, cow, cowHits, "cow inheriting the transponder protection");
        logEvery(h, be, batch, 40);
        h.succeedWhen(() -> {
            if (!friendly(be, cow)) throw new GameTestAssertException("cow does not inherit the protection");
            if (friendly(be, husk)) throw new GameTestAssertException("husk inherits the protection");
            if (huskHits.get() == 0) throw new GameTestAssertException("husk in the zone (not inheriting) not hit yet");
            FcsMod.LOG.info("[radar_fcs test] {}: cow protected, husk hit {} times", batch, huskHits.get());
        });
    }

    /** Типы: враждебные мобы не наследуют защиту, мирные - наследуют. */
    @GameTest(template = ARENA, batch = "iff_inherit", timeoutTicks = 500)
    public static void inheritByType(GameTestHelper h) {
        inheritCase(h, "iff_inherit", new dev.radarfcs.iff.InheritPolicy(
                dev.radarfcs.core.TargetCategory.ALL & ~dev.radarfcs.core.TargetCategory.HOSTILE.bit, List.of(), List.of()));
    }

    /** Типы выключены все, но корова в белом списке наследования - наследует только она. */
    @GameTest(template = ARENA, batch = "iff_inherit_white", timeoutTicks = 500)
    public static void inheritByWhitelist(GameTestHelper h) {
        inheritCase(h, "iff_inherit_white", new dev.radarfcs.iff.InheritPolicy(0, List.of("minecraft:cow"), List.of()));
    }

    /** Все типы наследуют, но нежить (#minecraft:undead) в чёрном списке наследования - не наследует. */
    @GameTest(template = ARENA, batch = "iff_inherit_black", timeoutTicks = 500)
    public static void inheritByBlacklist(GameTestHelper h) {
        inheritCase(h, "iff_inherit_black", new dev.radarfcs.iff.InheritPolicy(dev.radarfcs.core.TargetCategory.ALL, List.of(),
                List.of("#minecraft:undead")));
    }

    /** Имя транспондера на корабле - имя цели у вычислителя (трек корабля). */
    @GameTest(template = ARENA, batch = "iff_ship_name", timeoutTicks = 200)
    public static void transponderNamesShip(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> { s.iffWhitelist = true; s.iffCodes.add("4242"); });   // корабль чужой - его видно в целях
        SableTestSupport.NAME.set("Аврора");
        try {
            SableTestSupport.ship(h, new BlockPos(46, 9, 22), 3, new Vec3(0, 0, 0.12), 14, "1111", TransponderMode.SUBLEVEL, 12);
        } finally {
            SableTestSupport.NAME.remove();
        }
        h.succeedWhen(() -> {
            var ship = be.sensor().all().stream().filter(t -> t.kind == dev.radarfcs.core.Track.Kind.SHIP).findFirst().orElse(null);
            if (ship == null) throw new GameTestAssertException("ship not seen");
            if (!"Аврора".equals(ship.name) || !"Аврора".equals(ship.tagName)) {
                throw new GameTestAssertException("ship name " + ship.name + " / tag " + ship.tagName);
            }
        });
    }

    /**
     * Монитор показывает наземные транспондеры с именами (свой - по правилу сети), значки и подписи настраиваются
     * и сохраняются; имя транспондера чистится (служебные символы, длина).
     */
    @GameTest(template = ARENA, batch = "iff_monitor", timeoutTicks = 200)
    public static void monitorShowsTransponderNames(GameTestHelper h) {
        floor(h);
        var mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(dev.radarfcs.block.RadarMonitorBlock.FACING, Direction.SOUTH);
        BlockPos monPos = new BlockPos(10, 2, 20);
        h.setBlock(monPos, mon);
        FcsGameTests.CREATED.add(h.absolutePos(monPos));
        IffTransponderBlockEntity t = transponder(h, new BlockPos(18, 1, 26), "", TransponderMode.RADIUS, 4);
        t.apply(new dev.radarfcs.iff.TransponderSettings("", "База-1", TransponderMode.RADIUS, 4, dev.radarfcs.iff.InheritPolicy.ALL), null);
        String longName = "§cОчень длинное имя транспондера для проверки длины";
        IffTransponderBlockEntity t2 = transponder(h, new BlockPos(14, 1, 30), "", TransponderMode.RADIUS, 4);
        t2.apply(new dev.radarfcs.iff.TransponderSettings("", longName, TransponderMode.RADIUS, 4, dev.radarfcs.iff.InheritPolicy.ALL), null);
        if (t2.name().length() > dev.radarfcs.iff.TransponderSettings.NAME_LENGTH || t2.name().contains("§")) h.fail("name not cleaned: " + t2.name());
        h.runAtTickTime(60, () -> {
            if (!(h.getLevel().getBlockEntity(h.absolutePos(monPos)) instanceof dev.radarfcs.block.RadarMonitorBlockEntity m)) {
                h.fail("monitor missing");
                return;
            }
            var mark = m.marks().stream().filter(x -> x.name().equals("База-1")).findFirst().orElse(null);
            if (mark == null) h.fail("monitor has no mark for the named transponder: " + m.marks());
            else if (!mark.friendly()) h.fail("transponder mark is not friendly under the default rule");
            int packed = dev.radarfcs.block.MonitorIcons.pack(dev.radarfcs.block.MonitorIcons.Shape.CROSS, 2, 0);
            m.setIcon(dev.radarfcs.core.TargetCategory.SHIPS.ordinal(), packed);
            m.setLabels(dev.radarfcs.block.RadarMonitorBlockEntity.LABELS_ALWAYS);
            if (m.icon(dev.radarfcs.core.TargetCategory.SHIPS.ordinal()) != packed) h.fail("icon not set");
            if (m.labels() != dev.radarfcs.block.RadarMonitorBlockEntity.LABELS_ALWAYS) h.fail("labels not set");
            var tag = m.saveWithoutMetadata(h.getLevel().registryAccess());
            if (tag.getInt("Labels") != 1 || tag.getIntArray("Icons")[dev.radarfcs.core.TargetCategory.SHIPS.ordinal()] != packed) {
                h.fail("monitor settings not saved: " + tag);
            }
            m.setShow(m.show() & ~dev.radarfcs.block.RadarMonitorBlockEntity.Show.TRANSPONDERS.bit);
            if (!m.marks().isEmpty()) h.fail("transponder marks shown with the filter off");
            h.succeed();
        });
    }
}
