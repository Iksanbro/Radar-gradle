package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannon;
import static dev.radarfcs.gametest.FcsGameTests.block;
import static dev.radarfcs.gametest.FcsGameTests.countHits;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.link;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;
import static dev.radarfcs.gametest.SettingsGameTests.configure;
import static dev.radarfcs.gametest.SettingsGameTests.roundTicks;
import static dev.radarfcs.gametest.SettingsGameTests.tough;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.IffTransponderBlockEntity;
import dev.radarfcs.block.MemoryBlockEntity;
import dev.radarfcs.block.RadarMonitorBlock;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.iff.TransponderMode;
import dev.radarfcs.registry.FcsRegistry;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Электропитание (FE): вычислитель без энергии не стреляет, с ней - стреляет; блок памяти с питанием расширяет списки;
 * транспондер без питания не защищает; монитор без питания погашен; генератор Create Crafts & Additions питает
 * вычислитель через обычный приём FE.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class EnergyGameTests {

    private static final String ARENA = "arena";
    private static final BlockPos MOUNT = new BlockPos(10, 1, 32);
    private static final BlockPos FCS = new BlockPos(6, 1, 28);

    static void powered(ServerLevel l) {
        FcsGameTests.direct(l);
        FcsConfig.REQUIRE_POWER.set(true);
    }

    static void unpowered(ServerLevel l) {
        FcsGameTests.cleanup(l);
        FcsConfig.REQUIRE_POWER.set(false);
    }

    @BeforeBatch(batch = "energy_fcs") public static void b1(ServerLevel l) { powered(l); }
    @AfterBatch(batch = "energy_fcs") public static void a1(ServerLevel l) { unpowered(l); }
    @BeforeBatch(batch = "energy_memory") public static void b2(ServerLevel l) { powered(l); }
    @AfterBatch(batch = "energy_memory") public static void a2(ServerLevel l) { unpowered(l); }
    @BeforeBatch(batch = "energy_transponder") public static void b3(ServerLevel l) { powered(l); }
    @AfterBatch(batch = "energy_transponder") public static void a3(ServerLevel l) { unpowered(l); }
    @BeforeBatch(batch = "energy_monitor") public static void b4(ServerLevel l) { powered(l); }
    @AfterBatch(batch = "energy_monitor") public static void a4(ServerLevel l) { unpowered(l); }
    @BeforeBatch(batch = "energy_cca") public static void b5(ServerLevel l) { powered(l); }
    @AfterBatch(batch = "energy_cca") public static void a5(ServerLevel l) { unpowered(l); }

    /** FE в блок через обычный приём энергии (как кабель или генератор другого мода). */
    static int feed(GameTestHelper h, BlockPos rel, int fe) {
        IEnergyStorage e = h.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, h.absolutePos(rel), Direction.UP);
        if (e == null) throw new GameTestAssertException("no energy input at " + rel);
        return e.receiveEnergy(fe, false);
    }

    /** Подавать энергию каждый тик, пока flag. */
    static void feedEachTick(GameTestHelper h, BlockPos rel, int fe, AtomicBoolean flag) {
        h.onEachTick(() -> { if (flag.get()) feed(h, rel, fe); });
    }

    /** Без энергии вычислитель не стреляет («нет энергии»); подали 1024+ FE/т - огонь и попадание. */
    @GameTest(template = ARENA, batch = "energy_fcs", timeoutTicks = 500)
    public static void fireControlNeedsPower(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        Mob husk = tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16));
        AtomicInteger hits = countHits(h, husk);
        List<Long> rounds = roundTicks(h, MOUNT);
        AtomicBoolean power = new AtomicBoolean();
        feedEachTick(h, FCS, 1500, power);
        logEvery(h, be, "energy_fcs", 40);
        h.runAtTickTime(100, () -> {
            if (!rounds.isEmpty()) h.fail("fired without power: " + rounds);
            if (be.powered()) h.fail("fire control powered without energy");
            for (MountState m : be.mountStates()) if (m.status != Status.NO_ENERGY) h.fail("status without energy: " + m.status);
            if (!be.summary().getBoolean("Power") && be.summary().contains("Power")) {
                FcsMod.LOG.info("[radar_fcs test] energy_fcs: no power reported");
            }
            power.set(true);
        });
        h.succeedWhen(() -> {
            if (h.getTick() <= 100 || hits.get() == 0) throw new GameTestAssertException("not hit after powering yet");
            if (!be.powered()) throw new GameTestAssertException("not powered");
            FcsMod.LOG.info("[radar_fcs test] energy_fcs: hit {} times after power, buffer {} FE", hits.get(), be.energy().getEnergyStored());
        });
    }

    /**
     * Ёмкость списков 16 + 16 за блок памяти с питанием: корова - 17-я запись белого списка целей, не действует; блок
     * памяти в сети без питания - всё ещё 16; с питанием (240 FE/т) - 32, корова - цель, огонь.
     */
    @GameTest(template = ARENA, batch = "energy_memory", timeoutTicks = 600)
    public static void memoryBlockExtendsLists(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> {
            for (int i = 1; i <= 16; i++) s.whitelist.add("minecraft:dummy_" + i);
            s.whitelist.add("minecraft:cow");
        });
        AtomicBoolean fcsPower = new AtomicBoolean(true), memPower = new AtomicBoolean();
        feedEachTick(h, FCS, 1500, fcsPower);
        BlockPos mem = new BlockPos(4, 1, 28);
        h.setBlock(mem, FcsRegistry.MEMORY.get().defaultBlockState());
        FcsGameTests.CREATED.add(h.absolutePos(mem));
        link(h, mem, Direction.UP, FCS);
        feedEachTick(h, mem, 300, memPower);
        Mob cow = tough(movingTarget(h, EntityType.COW, new Vec3(40.5, 1, 24.5), 0.15, 16));
        AtomicInteger hits = countHits(h, cow);
        logEvery(h, be, "energy_memory", 40);
        h.runAtTickTime(120, () -> {
            if (be.capacity() != MemoryBlockEntity.BASE) h.fail("capacity with an unpowered memory block: " + be.capacity());
            if (hits.get() > 0 || !be.sensor().active().isEmpty()) h.fail("17th whitelist entry is active with 16 slots");
            if (be.effectiveSettings().whitelist.size() != 16) h.fail("effective whitelist " + be.effectiveSettings().whitelist.size());
            memPower.set(true);
        });
        h.succeedWhen(() -> {
            if (h.getTick() <= 120) throw new GameTestAssertException("waiting");
            if (be.capacity() != MemoryBlockEntity.BASE + MemoryBlockEntity.PER_BLOCK)
                throw new GameTestAssertException("capacity with a powered memory block: " + be.capacity());
            if (hits.get() == 0) throw new GameTestAssertException("cow (17th entry) not hit yet");
            FcsMod.LOG.info("[radar_fcs test] energy_memory: capacity {} with a powered memory block, cow hit {}", be.capacity(), hits.get());
        });
    }

    /** Транспондер с питанием бережёт корову; питание кончилось (запас 40 тиков) - зона пропала, огонь. */
    @GameTest(template = ARENA, batch = "energy_transponder", timeoutTicks = 600)
    public static void transponderNeedsPower(GameTestHelper h) {
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        configure(be, s -> s.whitelist.add("minecraft:cow"));
        AtomicBoolean fcsPower = new AtomicBoolean(true), tPower = new AtomicBoolean(true);
        feedEachTick(h, FCS, 1500, fcsPower);
        BlockPos tp = new BlockPos(48, 1, 30);
        IffTransponderBlockEntity t = IffGameTests.transponder(h, tp, "", TransponderMode.RADIUS, 12);
        feedEachTick(h, tp, 100, tPower);
        Mob cow = tough(movingTarget(h, EntityType.COW, new Vec3(46.5, 1, 26.5), 0.1, 4));
        AtomicInteger hits = countHits(h, cow);
        logEvery(h, be, "energy_transponder", 40);
        h.runAtTickTime(140, () -> {
            if (hits.get() > 0 || !IffGameTests.friendly(be, cow)) h.fail("powered transponder did not protect the cow");
            if (!t.powered()) h.fail("transponder not powered");
            tPower.set(false);
        });
        h.succeedWhen(() -> {
            if (h.getTick() <= 140) throw new GameTestAssertException("waiting");
            if (t.powered()) throw new GameTestAssertException("transponder still powered (buffer)");
            if (hits.get() == 0) throw new GameTestAssertException("cow not hit after the transponder lost power");
        });
    }

    /** Монитор 2x1 без питания погашен; энергия в один блок стены - экран работает (32 FE/т за блок). */
    @GameTest(template = ARENA, batch = "energy_monitor", timeoutTicks = 200)
    public static void monitorNeedsPower(GameTestHelper h) {
        floor(h);
        var mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH);
        for (int x = 2; x <= 3; x++) {
            h.setBlock(new BlockPos(x, 2, 20), mon);
            FcsGameTests.CREATED.add(h.absolutePos(new BlockPos(x, 2, 20)));
        }
        AtomicBoolean power = new AtomicBoolean();
        feedEachTick(h, new BlockPos(3, 2, 20), 100, power);
        h.runAtTickTime(60, () -> {
            if (!(h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 20))) instanceof RadarMonitorBlockEntity m)) {
                h.fail("monitor missing");
                return;
            }
            if (m.width() != 2 && m.master().width() != 2) h.fail("monitor wall not formed");
            if (m.powered()) h.fail("monitor powered without energy");
            power.set(true);
        });
        h.runAtTickTime(140, () -> {
            if (h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 20))) instanceof RadarMonitorBlockEntity m && !m.powered()) {
                h.fail("monitor not powered with energy in one wall block");
            }
            h.succeed();
        });
    }

    /** Творческий источник энергии Create Crafts & Additions рядом с вычислителем питает его: огонь и попадание. */
    @GameTest(template = ARENA, batch = "energy_cca", timeoutTicks = 500)
    public static void craftsAndAdditionsPowersFireControl(GameTestHelper h) {
        if (!ModList.get().isLoaded("createaddition")) {
            h.succeed();                                                    // без Crafts & Additions тест не применим
            return;
        }
        floor(h);
        autocannon(h, MOUNT, Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, FCS);
        h.setBlock(FCS.below(), block("createaddition:creative_energy").defaultBlockState());
        Mob husk = tough(movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16));
        AtomicInteger hits = countHits(h, husk);
        logEvery(h, be, "energy_cca", 40);
        h.succeedWhen(() -> {
            if (!be.powered()) throw new GameTestAssertException("not powered by Crafts & Additions");
            if (hits.get() == 0) throw new GameTestAssertException("not hit yet");
            FcsMod.LOG.info("[radar_fcs test] energy_cca: powered by createaddition:creative_energy, hits {}", hits.get());
        });
    }
}
