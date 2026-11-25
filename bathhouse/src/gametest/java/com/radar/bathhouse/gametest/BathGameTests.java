package com.radar.bathhouse.gametest;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.block.ApplianceBlock;
import com.radar.bathhouse.block.ApplianceBlockEntity;
import com.radar.bathhouse.block.BathGaugeBlock;
import com.radar.bathhouse.block.BathGaugeBlockEntity;
import com.radar.bathhouse.block.PlungeBlock;
import com.radar.bathhouse.block.PlungeBlockEntity;
import com.radar.bathhouse.climate.BathAssessment;
import com.radar.bathhouse.climate.BathType;
import com.radar.bathhouse.climate.SmokeVariant;
import com.radar.bathhouse.registry.ModBlocks;
import com.radar.bathhouse.registry.ModEffects;
import com.radar.bathhouse.session.BathSessionTracker;
import com.radar.bathhouse.session.PlungeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests: build small rooms in the test world and check scanning, classification, the climate model,
 * buff granting and the plunge rules. Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(Bathhouse.MOD_ID)
@PrefixGameTestTemplate(false)
public class BathGameTests {
    private static final String EMPTY = "empty";
    /** Interior of the standard test room: x, y, z in 2..4 (3x3x3), shell in 1..5. */
    private static final BlockPos HEATER = new BlockPos(3, 2, 3);
    private static final BlockPos GAUGE = new BlockPos(4, 3, 2);

    // ------------------------------------------------------------------------------------------------ helpers

    private static void shell(GameTestHelper helper, Block wall) {
        for (int x = 1; x <= 5; x++) {
            for (int y = 1; y <= 5; y++) {
                for (int z = 1; z <= 5; z++) {
                    boolean boundary = x == 1 || x == 5 || y == 1 || y == 5 || z == 1 || z == 5;
                    helper.setBlock(new BlockPos(x, y, z), boundary ? wall.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static ApplianceBlockEntity appliance(GameTestHelper helper, BlockPos pos, Block block) {
        helper.setBlock(pos, block.defaultBlockState().setValue(ApplianceBlock.FACING, Direction.NORTH));
        return helper.getBlockEntity(pos);
    }

    private static void chimneyOn(GameTestHelper helper, BlockPos stove) {
        for (int y = stove.getY() + 1; y <= 6; y++) {
            helper.setBlock(new BlockPos(stove.getX(), y, stove.getZ()), ModBlocks.CHIMNEY.get());
        }
    }

    private static BathGaugeBlockEntity gauge(GameTestHelper helper) {
        helper.setBlock(GAUGE, ModBlocks.BATH_GAUGE.get().defaultBlockState().setValue(BathGaugeBlock.FACING, Direction.SOUTH));
        BathGaugeBlockEntity gauge = helper.getBlockEntity(GAUGE);
        gauge.rescan();
        return gauge;
    }

    private static BathAssessment assess(BathGaugeBlockEntity gauge, double t, double rh, double smoke) {
        gauge.setClimate(t, rh, smoke);
        gauge.reassess();
        return gauge.assessment();
    }

    private static void expect(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    private static String describe(BathAssessment a) {
        return "type=" + a.type() + " variant=" + a.variant() + " closest=" + a.closest() + " problems="
                + a.problems().stream().map(c -> c.getString()).toList();
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos relative) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos abs = helper.absolutePos(relative);
        player.moveTo(abs.getX() + 0.5, abs.getY() + 0.2, abs.getZ() + 0.5);
        return player;
    }

    private static void remove(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    // ------------------------------------------------------------------------------------------------ tests

    @GameTest(template = EMPTY)
    public static void emberRoomHeatedWhite(GameTestHelper helper) {
        shell(helper, Blocks.SPRUCE_PLANKS);
        appliance(helper, HEATER, ModBlocks.INNER_HEATER.get());
        chimneyOn(helper, HEATER);
        BathGaugeBlockEntity gauge = gauge(helper);

        expect(helper, gauge.scan() != null && !gauge.leaky(), "room should be enclosed");
        expect(helper, gauge.scan().volume() == 24, "volume should be 27 - heater - 2 flue blocks, was " + gauge.scan().volume());
        expect(helper, gauge.variant() == SmokeVariant.WHITE, "a heater with a chimney is white, was " + gauge.variant());

        BathAssessment a = assess(gauge, 95, 10, 0);
        expect(helper, a.type() == BathType.EMBER, "95 C / 10 % in wood should be an ember room: " + describe(a));
        a = assess(gauge, 95, 25, 0);
        expect(helper, a.type() == null && a.closest() == BathType.EMBER, "25 % is too humid for an ember room: " + describe(a));
        a = assess(gauge, 125, 10, 0);
        expect(helper, a.type() == null, "125 C is dangerous, not a bath: " + describe(a));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void steamLodgeBlackThenGray(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        appliance(helper, HEATER, ModBlocks.INNER_HEATER.get());
        BathGaugeBlockEntity gauge = gauge(helper);

        expect(helper, gauge.variant() == SmokeVariant.BLACK, "no chimney anywhere is black, was " + gauge.variant());
        BathAssessment a = assess(gauge, 70, 55, 0);
        expect(helper, a.type() == BathType.STEAM && a.variant() == SmokeVariant.BLACK, "aired black lodge should be ready: " + describe(a));
        a = assess(gauge, 70, 55, 50);
        expect(helper, a.type() == null && a.closest() == BathType.STEAM, "smoky black lodge must not be ready: " + describe(a));

        // A flue in the ceiling, not on the stove: smoke passes through the room -> gray.
        helper.setBlock(new BlockPos(2, 5, 2), ModBlocks.CHIMNEY.get());
        helper.setBlock(new BlockPos(2, 6, 2), ModBlocks.CHIMNEY.get());
        gauge.rescan();
        expect(helper, gauge.variant() == SmokeVariant.GRAY, "ceiling flue makes it gray, was " + gauge.variant());
        a = assess(gauge, 70, 55, 20);
        expect(helper, a.type() == BathType.STEAM && a.variant() == SmokeVariant.GRAY, "gray lodge tolerates some smoke: " + describe(a));
        a = assess(gauge, 95, 10, 20);
        expect(helper, a.type() == BathType.EMBER && a.variant() == SmokeVariant.GRAY, "ember rooms can be gray too: " + describe(a));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void mistHallOnlyWhite(GameTestHelper helper) {
        shell(helper, Blocks.STONE_BRICKS);
        appliance(helper, HEATER, ModBlocks.STEAM_GENERATOR.get());
        BathGaugeBlockEntity gauge = gauge(helper);

        BathAssessment a = assess(gauge, 45, 95, 0);
        expect(helper, a.type() == BathType.MIST && a.variant() == SmokeVariant.WHITE, "stone + steam generator, 45 C / 95 %: " + describe(a));

        appliance(helper, new BlockPos(2, 2, 2), ModBlocks.IRON_STOVE.get());
        gauge.rescan();
        a = assess(gauge, 45, 95, 0);
        expect(helper, a.type() == null && a.closest() == BathType.MIST && a.variant() == SmokeVariant.BLACK,
                "a smoky stove makes the mist hall invalid: " + describe(a));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void woodRequiredForSteam(GameTestHelper helper) {
        shell(helper, Blocks.STONE_BRICKS);
        appliance(helper, HEATER, ModBlocks.INNER_HEATER.get());
        chimneyOn(helper, HEATER);
        BathGaugeBlockEntity gauge = gauge(helper);
        BathAssessment a = assess(gauge, 70, 55, 0);
        expect(helper, a.type() == null && a.closest() == BathType.STEAM, "a stone room is no steam lodge: " + describe(a));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void secondGaugeIsIgnored(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        ApplianceBlockEntity heater = appliance(helper, HEATER, ModBlocks.INNER_HEATER.get());
        BathGaugeBlockEntity first = gauge(helper);
        BlockPos otherPos = new BlockPos(2, 3, 4);
        helper.setBlock(otherPos, ModBlocks.BATH_GAUGE.get().defaultBlockState().setValue(BathGaugeBlock.FACING, Direction.NORTH));
        BathGaugeBlockEntity second = helper.getBlockEntity(otherPos);
        first.rescan();
        second.rescan();
        expect(helper, first.scan().duplicateGauge() != second.scan().duplicateGauge(), "exactly one gauge is the main one");
        BathGaugeBlockEntity main = first.scan().duplicateGauge() ? second : first;
        BathGaugeBlockEntity spare = main == first ? second : first;

        heater.setFuel(ApplianceBlockEntity.MAX_FUEL);
        for (int s = 0; s < 120; s++) {
            spare.simulate(1.0);
            heater.advanceSecond();
            main.simulate(1.0);
        }
        expect(helper, main.temperature() > spare.temperature() + 20,
                "the main gauge gets the heat: main " + main.temperature() + ", spare " + spare.temperature());
        BathAssessment a = assess(spare, 70, 55, 0);
        expect(helper, a.type() == null, "the spare gauge reports a duplicate: " + describe(a));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void openDoorLeaks(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        appliance(helper, HEATER, ModBlocks.INNER_HEATER.get());
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.OPEN, true);
        helper.setBlock(new BlockPos(3, 2, 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(new BlockPos(3, 3, 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        BathGaugeBlockEntity gauge = gauge(helper);
        expect(helper, gauge.leaky() && gauge.scan() == null, "an open door must leak");

        helper.setBlock(new BlockPos(3, 2, 1), door.setValue(DoorBlock.OPEN, false).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(new BlockPos(3, 3, 1), door.setValue(DoorBlock.OPEN, false).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        gauge.rescan();
        expect(helper, !gauge.leaky() && gauge.scan() != null, "a closed door seals the room");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void heaterWarmsRoomAndWaterMakesSteam(GameTestHelper helper) {
        shell(helper, Blocks.SPRUCE_PLANKS);
        ApplianceBlockEntity heater = appliance(helper, HEATER, ModBlocks.INNER_HEATER.get());
        chimneyOn(helper, HEATER);
        BathGaugeBlockEntity gauge = gauge(helper);
        heater.setFuel(ApplianceBlockEntity.MAX_FUEL);

        double start = Double.NaN;
        for (int s = 0; s < 360; s++) {
            if (s % 2 == 0) {
                gauge.rescan();
            }
            heater.advanceSecond();
            gauge.simulate(1.0);
            if (s == 0) {
                start = gauge.temperature();
            }
        }
        double hot = gauge.temperature();
        expect(helper, hot > 80 && hot < 120, "6 minutes of firing should heat a 3x3x3 room to 80-120 C, got " + hot + " (from " + start + ")");
        expect(helper, gauge.humidity() < 5, "dry heat should be dry, got " + gauge.humidity());

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        for (int i = 0; i < 3; i++) {
            heater.throwWater(helper.getLevel(), ApplianceBlock.LADLE_PORTION, player);
            heater.advanceSecond();
            gauge.simulate(1.0);
        }
        double humid = gauge.humidity();
        expect(helper, humid > 7, "three ladles should raise humidity noticeably, got " + humid);
        gauge.reassess();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void sessionGrantsLongBuff(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        appliance(helper, HEATER, ModBlocks.INNER_HEATER.get());
        BathGaugeBlockEntity gauge = gauge(helper);
        assess(gauge, 70, 55, 0); // black lodge, aired
        ServerPlayer player = player(helper, new BlockPos(2, 2, 4));
        expect(helper, gauge.isInside(player), "player should be inside the room");

        for (int i = 0; i < BathConfig.SESSION_SECONDS.get(); i++) {
            BathSessionTracker.visit(player, gauge);
        }
        MobEffectInstance effect = player.getEffect(ModEffects.FORTITUDE);
        expect(helper, effect != null, "a full session should grant Fortitude");
        expect(helper, effect.getAmplifier() == BathConfig.BLACK_AMPLIFIER_BONUS.get(), "black bath is stronger, amplifier " + effect.getAmplifier());
        expect(helper, effect.getDuration() >= BathConfig.BUFF_DURATION_TICKS.get() - 5, "buff should last as long as Hero of the Village");
        expect(helper, BathSessionTracker.isWarm(player), "a steam lodge warms the player up");
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void coldPlungeContrast(GameTestHelper helper) {
        BlockPos tubPos = new BlockPos(8, 1, 8);
        helper.setBlock(tubPos, ModBlocks.COLD_PLUNGE.get().defaultBlockState().setValue(PlungeBlock.LEVEL, 3));
        PlungeBlockEntity tub = helper.getBlockEntity(tubPos);
        tub.setTemperature(8.0);

        // Warm up in a steam lodge first.
        shell(helper, Blocks.OAK_PLANKS);
        appliance(helper, HEATER, ModBlocks.INNER_HEATER.get());
        BathGaugeBlockEntity gauge = gauge(helper);
        assess(gauge, 70, 55, 0);
        ServerPlayer warm = player(helper, new BlockPos(2, 2, 4));
        BathSessionTracker.visit(warm, gauge);

        BlockPos abs = helper.absolutePos(tubPos);
        warm.moveTo(abs.getX() + 0.5, abs.getY() + 0.2, abs.getZ() + 0.5);
        for (int t = 0; t < 15 * 20; t++) {
            PlungeTracker.tick(warm);
        }
        warm.moveTo(abs.getX() + 0.5, abs.getY() + 1.2, abs.getZ() - 1.5);
        PlungeTracker.tick(warm);
        expect(helper, warm.hasEffect(ModEffects.TEMPERING), "a 15 s dip after the steam room grants Tempering");
        expect(helper, !BathSessionTracker.isWarm(warm), "the contrast uses up the warmth");

        // Without warming up: cold shock and no reward.
        ServerPlayer cold = player(helper, tubPos);
        for (int t = 0; t < 15 * 20; t++) {
            PlungeTracker.tick(cold);
        }
        cold.moveTo(abs.getX() + 0.5, abs.getY() + 1.2, abs.getZ() - 1.5);
        PlungeTracker.tick(cold);
        expect(helper, !cold.hasEffect(ModEffects.TEMPERING), "no contrast without warming up");

        remove(helper, warm);
        remove(helper, cold);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void hotPlungeChestLevel(GameTestHelper helper) {
        BlockPos tubPos = new BlockPos(8, 1, 4);
        int soak = BathConfig.HOT_SOAK_SECONDS.get() + 1;

        // Water to the brim (level 3) covers the heart: no soaking progress.
        helper.setBlock(tubPos, ModBlocks.HOT_PLUNGE.get().defaultBlockState().setValue(PlungeBlock.LEVEL, 3));
        PlungeBlockEntity tub = helper.getBlockEntity(tubPos);
        tub.setTemperature(40.0);
        ServerPlayer player = player(helper, tubPos);
        for (int t = 0; t < soak * 20; t++) {
            PlungeTracker.tick(player);
        }
        expect(helper, !player.hasEffect(ModEffects.BLISS), "water above the chest must not count");

        // Scoop down to chest level.
        tub.removeContent(1);
        tub.setTemperature(40.0);
        BlockPos abs = helper.absolutePos(tubPos);
        player.moveTo(abs.getX() + 0.5, abs.getY() + 1.2, abs.getZ() - 1.5);
        PlungeTracker.tick(player);
        player.moveTo(abs.getX() + 0.5, abs.getY() + 0.2, abs.getZ() + 0.5);
        for (int t = 0; t < soak * 20; t++) {
            PlungeTracker.tick(player);
        }
        MobEffectInstance bliss = player.getEffect(ModEffects.BLISS);
        expect(helper, bliss != null && bliss.getAmplifier() == 0, "a good soak at chest level grants Bliss I");
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void dataIsLoaded(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String[] recipes = {"bath_gauge", "inner_heater", "outer_heater", "brick_stove", "iron_stove", "electric_heater",
                "steam_generator", "warm_lounger", "chimney", "cold_plunge", "smoke_vent", "ladle", "bath_broom", "scrub_mitt",
                "sawdust", "hot_plunge", "sawdust_plunge"};
        for (String id : recipes) {
            expect(helper, server.getRecipeManager().byKey(Bathhouse.id(id)).isPresent(), "missing recipe " + id);
        }
        for (var block : ModBlocks.BLOCKS.getEntries()) {
            var key = block.get().getLootTable();
            expect(helper, server.reloadableRegistries().getLootTable(key) != net.minecraft.world.level.storage.loot.LootTable.EMPTY,
                    "missing loot table " + key.location());
        }
        expect(helper, helper.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                .containsKey(com.radar.bathhouse.registry.ModDamageTypes.OVERHEAT), "missing overheat damage type");
        expect(helper, Blocks.OAK_PLANKS.defaultBlockState().is(com.radar.bathhouse.registry.ModTags.BATH_WOOD), "oak planks are wood");
        expect(helper, Blocks.CALCITE.defaultBlockState().is(com.radar.bathhouse.registry.ModTags.BATH_STONE), "calcite is stone");
        expect(helper, Blocks.WHITE_WOOL.defaultBlockState().is(com.radar.bathhouse.registry.ModTags.BATH_INSULATION), "wool insulates");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void hotPlungeHeatsUp(GameTestHelper helper) {
        BlockPos tubPos = new BlockPos(8, 1, 4);
        helper.setBlock(tubPos, ModBlocks.HOT_PLUNGE.get().defaultBlockState().setValue(PlungeBlock.LEVEL, 2));
        PlungeBlockEntity tub = helper.getBlockEntity(tubPos);
        tub.setFuel(PlungeBlockEntity.MAX_FUEL);
        for (int s = 0; s < 600; s++) {
            tub.advanceSecond();
        }
        double t = tub.temperature();
        expect(helper, t >= 38 && t <= 43, "the damper should hold the hot plunge around 38-42 C, got " + t);
        helper.succeed();
    }
}
