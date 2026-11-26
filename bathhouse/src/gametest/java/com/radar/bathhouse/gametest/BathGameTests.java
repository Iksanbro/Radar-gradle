package com.radar.bathhouse.gametest;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.block.BathGaugeBlock;
import com.radar.bathhouse.block.BathGaugeBlockEntity;
import com.radar.bathhouse.climate.BathAssessment;
import com.radar.bathhouse.climate.BathType;
import com.radar.bathhouse.climate.SmokeVariant;
import com.radar.bathhouse.item.WaterPortions;
import com.radar.bathhouse.plunge.PlungeBenchBlock;
import com.radar.bathhouse.plunge.PlungeControllerBlockEntity;
import com.radar.bathhouse.plunge.PlungeKind;
import com.radar.bathhouse.plunge.PlungeStoveBlock;
import com.radar.bathhouse.plunge.PlungeStoveBlockEntity;
import com.radar.bathhouse.plunge.PlungeThermometerBlock;
import com.radar.bathhouse.registry.ModBlocks;
import com.radar.bathhouse.registry.ModEffects;
import com.radar.bathhouse.registry.ModTags;
import com.radar.bathhouse.session.BathSessionTracker;
import com.radar.bathhouse.session.PlungeTracker;
import com.radar.bathhouse.stove.ChamberType;
import com.radar.bathhouse.stove.FireboxBlock;
import com.radar.bathhouse.stove.StoneQuality;
import com.radar.bathhouse.stove.StoveBlockEntity;
import com.radar.bathhouse.stove.StovePartBlock;
import com.radar.bathhouse.stove.StovePartBlockEntity;
import com.radar.bathhouse.stove.StoveStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * In-game tests: build rooms, stoves and plunges in the test world and check assembly, classification, the climate
 * model, steam levels and the plunge rules. Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(Bathhouse.MOD_ID)
@PrefixGameTestTemplate(false)
public class BathGameTests {
    private static final String EMPTY = "empty";
    /** Standard test room: interior x, y, z in 2..4 (3x3x3), shell in 1..5. The stove stands in the middle. */
    private static final BlockPos FIREBOX = new BlockPos(3, 2, 3);
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

    /** Firebox at {@link #FIREBOX}, optional ash pit in the floor, optional chamber on top, optional chimney through the ceiling. */
    private static StoveBlockEntity stove(GameTestHelper helper, Block firebox, boolean ashPit, @Nullable Block chamber, boolean chimney) {
        helper.setBlock(FIREBOX, firebox.defaultBlockState().setValue(FireboxBlock.FACING, Direction.NORTH));
        if (ashPit) {
            helper.setBlock(FIREBOX.below(), ModBlocks.ASH_PIT.get().defaultBlockState());
        }
        int top = FIREBOX.getY();
        if (chamber != null) {
            helper.setBlock(FIREBOX.above(), chamber.defaultBlockState());
            top++;
        }
        if (chimney) {
            for (int y = top + 1; y <= 6; y++) {
                helper.setBlock(new BlockPos(FIREBOX.getX(), y, FIREBOX.getZ()), ModBlocks.CHIMNEY.get());
            }
        }
        return helper.getBlockEntity(FIREBOX);
    }

    private static StovePartBlockEntity chamber(GameTestHelper helper) {
        return helper.getBlockEntity(FIREBOX.above());
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

    private static ServerPlayer player(GameTestHelper helper, BlockPos relative, double dy) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos abs = helper.absolutePos(relative);
        player.moveTo(abs.getX() + 0.5, abs.getY() + dy, abs.getZ() + 0.5);
        return player;
    }

    private static void remove(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    // ------------------------------------------------------------------------------------------------ stove

    @GameTest(template = EMPTY)
    public static void stoveAssembly(GameTestHelper helper) {
        helper.setBlock(FIREBOX, ModBlocks.BRICK_FIREBOX.get().defaultBlockState().setValue(FireboxBlock.FACING, Direction.NORTH));
        helper.setBlock(FIREBOX.below(), ModBlocks.ASH_PIT.get());
        helper.setBlock(FIREBOX.above(), ModBlocks.CLOSED_HEATER.get());
        helper.setBlock(FIREBOX.above(2), ModBlocks.DAMPER.get().defaultBlockState().setValue(StovePartBlock.ACTIVE, false));
        helper.setBlock(FIREBOX.above(3), ModBlocks.WATER_TANK.get());
        helper.setBlock(FIREBOX.above(4), ModBlocks.CHIMNEY.get());
        helper.setBlock(FIREBOX.north(), ModBlocks.FUEL_TUNNEL.get());
        helper.setBlock(FIREBOX.west(), ModBlocks.STOVE_CLADDING.get());
        helper.setBlock(FIREBOX.above().east(), ModBlocks.STOVE_CLADDING.get());

        BlockPos core = helper.absolutePos(FIREBOX);
        StoveStructure.Assembly asm = StoveStructure.assemble(helper.getLevel(), core);
        expect(helper, asm.ashPit() != null, "ash pit under the firebox");
        expect(helper, asm.chamber() == ChamberType.CLOSED, "closed heater on top, got " + asm.chamber());
        expect(helper, asm.chimney() && asm.flue().size() == 3, "flue of damper, tank and chimney: " + asm.flue());
        expect(helper, asm.damper() != null && !asm.damperOpen(), "the damper is closed");
        expect(helper, asm.tanks().size() == 1, "one water tank");
        expect(helper, asm.tunnel() != null, "fuel tunnel in front of the door");
        expect(helper, asm.cladding() == 2, "two cladding blocks, got " + asm.cladding());

        for (BlockPos part : new BlockPos[]{FIREBOX.below(), FIREBOX.above(), FIREBOX.above(2), FIREBOX.above(3),
                FIREBOX.above(4), FIREBOX.north(), FIREBOX.west(), FIREBOX.above().east()}) {
            BlockPos found = StoveStructure.findCore(helper.getLevel(), helper.absolutePos(part));
            expect(helper, core.equals(found), "part at " + part + " should lead to the firebox, got " + found);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void steamLodgeWhiteBlackGray(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        stove(helper, ModBlocks.STEEL_FIREBOX.get(), true, ModBlocks.CLOSED_HEATER.get(), true);
        BathGaugeBlockEntity gauge = gauge(helper);
        expect(helper, gauge.scan() != null && !gauge.leaky(), "room should be enclosed");
        expect(helper, gauge.scan().sources().size() == 1, "one stove found, got " + gauge.scan().sources());
        expect(helper, gauge.variant() == SmokeVariant.WHITE, "stove with a chimney is white, was " + gauge.variant());
        BathAssessment a = assess(gauge, 70, 55, 0);
        expect(helper, a.type() == BathType.STEAM, "70 C / 55 % with an inner heater is a steam lodge: " + describe(a));
        a = assess(gauge, 95, 10, 0);
        expect(helper, a.type() == null && a.closest() == BathType.EMBER, "an inner heater makes no ember room: " + describe(a));

        for (int y = 4; y <= 6; y++) {
            helper.setBlock(new BlockPos(3, y, 3), y == 5 ? Blocks.OAK_PLANKS : Blocks.AIR);
        }
        gauge.rescan();
        expect(helper, gauge.variant() == SmokeVariant.BLACK, "no chimney is black, was " + gauge.variant());

        helper.setBlock(new BlockPos(2, 5, 2), ModBlocks.CHIMNEY.get());
        helper.setBlock(new BlockPos(2, 6, 2), ModBlocks.CHIMNEY.get());
        gauge.rescan();
        expect(helper, gauge.variant() == SmokeVariant.GRAY, "ceiling flue makes it gray, was " + gauge.variant());
        a = assess(gauge, 70, 55, 20);
        expect(helper, a.type() == BathType.STEAM && a.variant() == SmokeVariant.GRAY, "gray lodge tolerates some smoke: " + describe(a));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void emberRoomElectricOpenHeater(GameTestHelper helper) {
        shell(helper, Blocks.SPRUCE_PLANKS);
        stove(helper, ModBlocks.HEATING_ELEMENT.get(), false, ModBlocks.OPEN_HEATER.get(), false);
        BathGaugeBlockEntity gauge = gauge(helper);
        expect(helper, gauge.variant() == SmokeVariant.WHITE, "an electric stove never smokes");
        BathAssessment a = assess(gauge, 95, 10, 0);
        expect(helper, a.type() == BathType.EMBER, "open heater, 95 C / 10 %: " + describe(a));
        a = assess(gauge, 70, 55, 0);
        expect(helper, a.type() == null && a.closest() == BathType.STEAM, "an outer heater makes no steam lodge: " + describe(a));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void mistHallBoilerStoveAndGenerator(GameTestHelper helper) {
        shell(helper, Blocks.STONE_BRICKS);
        stove(helper, ModBlocks.STEEL_FIREBOX.get(), true, ModBlocks.BOILER.get(), true);
        BathGaugeBlockEntity gauge = gauge(helper);
        BathAssessment a = assess(gauge, 45, 95, 0);
        expect(helper, a.type() == BathType.MIST && a.variant() == SmokeVariant.WHITE, "boiler stove in stone: " + describe(a));

        helper.setBlock(new BlockPos(3, 6, 3), Blocks.AIR);
        helper.setBlock(new BlockPos(3, 5, 3), Blocks.STONE_BRICKS);
        helper.setBlock(new BlockPos(3, 4, 3), Blocks.AIR);
        gauge.rescan();
        a = assess(gauge, 45, 95, 0);
        expect(helper, a.type() == null && a.variant() == SmokeVariant.BLACK, "a smoky stove spoils a mist hall: " + describe(a));

        helper.setBlock(FIREBOX.above(), Blocks.AIR);
        helper.setBlock(FIREBOX, ModBlocks.STEAM_GENERATOR.get());
        helper.setBlock(FIREBOX.below(), Blocks.STONE_BRICKS);
        gauge.rescan();
        a = assess(gauge, 45, 95, 0);
        expect(helper, a.type() == BathType.MIST, "steam generator works too: " + describe(a));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void stoveHeatsStonesAndMakesSteam(GameTestHelper helper) {
        shell(helper, Blocks.SPRUCE_PLANKS);
        StoveBlockEntity stove = stove(helper, ModBlocks.STEEL_FIREBOX.get(), true, ModBlocks.CLOSED_HEATER.get(), true);
        StovePartBlockEntity heater = chamber(helper);
        BathGaugeBlockEntity gauge = gauge(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        expect(helper, !stove.throwWater(helper.getLevel(), heater, WaterPortions.LADLE_PORTION, false, player),
                "an empty heater takes no water");
        expect(helper, heater.addStones(new ItemStack(Items.BASALT, 64), 64) == 12, "the inner heater holds 12 stones");
        expect(helper, heater.stoneQuality() == 3.0, "basalt is excellent");
        stove.setFuel(StoveBlockEntity.MAX_FUEL);

        for (int s = 0; s < 480; s++) {
            if (s % 2 == 0) {
                gauge.rescan();
            }
            stove.advanceSecond();
            gauge.simulate(1.0);
        }
        double room = gauge.temperature();
        double stones = stove.stoneTemperature();
        expect(helper, room > 75 && room < 120, "8 minutes of firing should heat the room to 75-120 C, got " + room);
        expect(helper, stones > 250, "stones of an inner heater get very hot, got " + stones);

        double dry = gauge.humidity();
        for (int i = 0; i < 4; i++) {
            stove.throwWater(helper.getLevel(), heater, WaterPortions.LADLE_PORTION, i % 2 == 0, player);
            stove.advanceSecond();
            gauge.simulate(1.0);
        }
        expect(helper, gauge.humidity() > dry + 8, "four ladles should raise humidity, " + dry + " -> " + gauge.humidity());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ashPitDraftAndDamperSmoke(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        StoveBlockEntity stove = stove(helper, ModBlocks.STEEL_FIREBOX.get(), false, ModBlocks.CLOSED_HEATER.get(), true);
        stove.setFuel(StoveBlockEntity.MAX_FUEL);
        BathGaugeBlockEntity gauge = gauge(helper);
        gauge.simulate(1.0);
        stove.advanceSecond();
        expect(helper, stove.draft() < 1.0, "no ash pit: poor draft");

        helper.setBlock(FIREBOX.below(), ModBlocks.ASH_PIT.get());
        stove.advanceSecond();
        expect(helper, stove.draft() == 1.0, "ash pit gives full draft");
        for (int s = 0; s < 130; s++) {
            stove.advanceSecond();
        }
        StovePartBlockEntity ashPit = helper.getBlockEntity(FIREBOX.below());
        expect(helper, ashPit.ash() >= 1, "two minutes of burning leave ash, got " + ashPit.ash());

        // Shut the damper while burning: smoke backs into the room even with a chimney.
        helper.setBlock(new BlockPos(3, 4, 3), ModBlocks.DAMPER.get().defaultBlockState().setValue(StovePartBlock.ACTIVE, false));
        gauge.rescan();
        for (int s = 0; s < 20; s++) {
            stove.advanceSecond();
            gauge.simulate(1.0);
        }
        expect(helper, gauge.smoke() > 10, "closed damper while burning fills the room with smoke, got " + gauge.smoke());
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ levels

    private static void session(ServerPlayer player, BathGaugeBlockEntity gauge, int toolUses) {
        for (int i = 0; i < BathConfig.SESSION_SECONDS.get() && !player.hasEffect(gauge.assessment().type().effects().getFirst()); i++) {
            BathSessionTracker.visit(player, gauge);
            if (i >= 2 && i < 2 + toolUses) {
                BathSessionTracker.recordTool(player, 0.0);
            }
        }
    }

    @GameTest(template = EMPTY)
    public static void steamLevelThreeGivesAllParts(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        stove(helper, ModBlocks.BRICK_FIREBOX.get(), true, ModBlocks.CLOSED_HEATER.get(), true);
        chamber(helper).addStones(new ItemStack(Items.SMOOTH_BASALT, 12), 12);
        BathGaugeBlockEntity gauge = gauge(helper);
        BathAssessment a = assess(gauge, 70, 55, 0);
        expect(helper, a.type() == BathType.STEAM, "steam lodge expected: " + describe(a));
        expect(helper, gauge.bathCriteria(BathType.STEAM).stream().allMatch(c -> c.met()),
                "brick stove, ash pit, full heater, wooden room: all bath criteria met");

        ServerPlayer player = player(helper, new BlockPos(2, 2, 4), 0.0);
        session(player, gauge, 3);
        expect(helper, player.hasEffect(ModEffects.FORTITUDE) && player.hasEffect(ModEffects.PROWESS)
                && player.hasEffect(ModEffects.STEADFASTNESS), "steam level 3 grants all three parts");
        expect(helper, player.getEffect(ModEffects.FORTITUDE).getDuration() >= BathConfig.BUFF_DURATION_TICKS.get() - 5,
                "as long as Hero of the Village");
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void steamLevelOneGivesFirstPart(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        stove(helper, ModBlocks.STEEL_FIREBOX.get(), false, ModBlocks.CLOSED_HEATER.get(), true);
        BathGaugeBlockEntity gauge = gauge(helper);
        BathAssessment a = assess(gauge, 61, 41, 0); // ready, but not in the ideal band
        expect(helper, a.type() == BathType.STEAM, "steam lodge expected: " + describe(a));

        ServerPlayer player = player(helper, new BlockPos(2, 2, 4), 0.0);
        session(player, gauge, 0);
        expect(helper, player.hasEffect(ModEffects.FORTITUDE), "level 1 grants the first part");
        expect(helper, !player.hasEffect(ModEffects.PROWESS) && !player.hasEffect(ModEffects.STEADFASTNESS),
                "a poor bath and a sloppy session give level 1 only");
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void blackBathBonusRaisesLevel(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        stove(helper, ModBlocks.STEEL_FIREBOX.get(), false, ModBlocks.CLOSED_HEATER.get(), false);
        BathGaugeBlockEntity gauge = gauge(helper);
        BathAssessment a = assess(gauge, 70, 55, 0); // aired black lodge, ideal climate
        expect(helper, a.type() == BathType.STEAM && a.variant() == SmokeVariant.BLACK, "aired black lodge: " + describe(a));
        ServerPlayer player = player(helper, new BlockPos(2, 2, 4), 0.0);
        session(player, gauge, 0);
        // finish + steady + unbroken + black bonus = 4 points -> level 2
        expect(helper, player.hasEffect(ModEffects.PROWESS) && !player.hasEffect(ModEffects.STEADFASTNESS),
                "black bonus lifts an ordinary session to level 2");
        remove(helper, player);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ failed steaming

    private static void failFor(ServerPlayer player, BathGaugeBlockEntity gauge, int seconds) {
        for (int i = 0; i < seconds; i++) {
            BathSessionTracker.visit(player, gauge);
        }
    }

    @GameTest(template = EMPTY)
    public static void failedSteamGivesHalfInverseDebuff(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        stove(helper, ModBlocks.STEEL_FIREBOX.get(), true, ModBlocks.CLOSED_HEATER.get(), true);
        BathGaugeBlockEntity gauge = gauge(helper);
        BathAssessment a = assess(gauge, 70, 20, 0); // hot enough for a lodge, but far too dry
        expect(helper, a.type() == null && a.closest() == BathType.STEAM && a.problems().size() == 1, "one problem: " + describe(a));
        expect(helper, BathSessionTracker.isFailedAttempt(a, gauge), "a hot but wrong bath is a failed attempt");

        ServerPlayer player = player(helper, new BlockPos(2, 2, 4), 0.0);
        failFor(player, gauge, BathConfig.FAILED_STEAM_SECONDS.get() - 1);
        expect(helper, !player.hasEffect(ModEffects.FRAILTY), "nothing before the time is up");
        failFor(player, gauge, 1);
        expect(helper, player.hasEffect(ModEffects.FRAILTY) && !player.hasEffect(ModEffects.LIMPNESS),
                "one problem: the first part inverted");
        int duration = player.getEffect(ModEffects.FRAILTY).getDuration();
        expect(helper, duration <= BathConfig.BUFF_DURATION_TICKS.get() / 2 && duration > BathConfig.BUFF_DURATION_TICKS.get() / 2 - 10,
                "half as long as the buff, got " + duration);
        expect(helper, Math.abs(player.getMaxHealth() - 18.0F) < 0.01F, "half of +4 health, inverted: 18, got " + player.getMaxHealth());

        // A proper steam afterwards washes the debuff away.
        assess(gauge, 70, 55, 0);
        session(player, gauge, 0);
        expect(helper, player.hasEffect(ModEffects.FORTITUDE) && !player.hasEffect(ModEffects.FRAILTY),
                "a good steam replaces the debuff");
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void moreProblemsMoreDebuffs(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        stove(helper, ModBlocks.STEEL_FIREBOX.get(), true, ModBlocks.CLOSED_HEATER.get(), false); // black
        BathGaugeBlockEntity gauge = gauge(helper);
        BathAssessment a = assess(gauge, 70, 20, 50); // too dry and still smoky
        expect(helper, a.closest() == BathType.STEAM && a.problems().size() == 2, "two problems: " + describe(a));
        ServerPlayer player = player(helper, new BlockPos(2, 2, 4), 0.0);
        failFor(player, gauge, BathConfig.FAILED_STEAM_SECONDS.get());
        expect(helper, player.hasEffect(ModEffects.FRAILTY) && player.hasEffect(ModEffects.LIMPNESS)
                && !player.hasEffect(ModEffects.UNSTEADINESS), "two problems: two inverted parts");
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void noDebuffWhileHeatingUp(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        stove(helper, ModBlocks.STEEL_FIREBOX.get(), true, ModBlocks.CLOSED_HEATER.get(), true);
        BathGaugeBlockEntity gauge = gauge(helper);
        BathAssessment a = assess(gauge, 45, 10, 0); // still heating
        expect(helper, !BathSessionTracker.isFailedAttempt(a, gauge), "a room still heating up is not a failed attempt: " + describe(a));
        ServerPlayer player = player(helper, new BlockPos(2, 2, 4), 0.0);
        failFor(player, gauge, BathConfig.FAILED_STEAM_SECONDS.get() * 2);
        expect(helper, !player.hasEffect(ModEffects.FRAILTY) && !player.hasEffect(ModEffects.HEAVINESS)
                && !player.hasEffect(ModEffects.CLOGGED_PORES), "no debuffs while waiting for the heat");
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void failedEmberRoomSlowsHalfAsMuch(GameTestHelper helper) {
        shell(helper, Blocks.SPRUCE_PLANKS);
        stove(helper, ModBlocks.HEATING_ELEMENT.get(), false, ModBlocks.OPEN_HEATER.get(), false);
        BathGaugeBlockEntity gauge = gauge(helper);
        BathAssessment a = assess(gauge, 95, 30, 0); // too humid for dry heat
        expect(helper, a.closest() == BathType.EMBER && a.problems().size() == 1, "one problem: " + describe(a));
        ServerPlayer player = player(helper, new BlockPos(2, 2, 4), 0.0);
        double base = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        failFor(player, gauge, BathConfig.FAILED_STEAM_SECONDS.get());
        double slowed = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        expect(helper, player.hasEffect(ModEffects.HEAVINESS), "failed ember room: Heaviness");
        expect(helper, Math.abs(slowed / base - 0.95) < 1e-6, "Lightness gives +10 %, Heaviness takes 5 %: " + base + " -> " + slowed);
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void toxinsAndUnsteadinessWork(GameTestHelper helper) {
        var level = helper.getLevel();
        var poison = new net.minecraft.world.damagesource.DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(net.neoforged.neoforge.common.NeoForgeMod.POISON_DAMAGE));
        var clean = helper.spawn(net.minecraft.world.entity.EntityType.PIG, new BlockPos(2, 2, 2));
        var toxic = helper.spawn(net.minecraft.world.entity.EntityType.PIG, new BlockPos(6, 2, 2));
        toxic.addEffect(new net.minecraft.world.effect.MobEffectInstance(ModEffects.TOXINS, 200));
        float before = clean.getHealth();
        clean.hurt(poison, 2.0F);
        toxic.invulnerableTime = 0;
        toxic.hurt(poison, 2.0F);
        expect(helper, Math.abs((before - clean.getHealth()) - 2.0F) < 0.01F, "plain poison hurts 2");
        expect(helper, Math.abs((before - toxic.getHealth()) - 3.0F) < 0.01F, "with Toxins poison hurts 50 % more, lost " + (before - toxic.getHealth()));

        var steady = helper.spawn(net.minecraft.world.entity.EntityType.PIG, new BlockPos(2, 2, 6));
        var shaky = helper.spawn(net.minecraft.world.entity.EntityType.PIG, new BlockPos(6, 2, 6));
        shaky.addEffect(new net.minecraft.world.effect.MobEffectInstance(ModEffects.UNSTEADINESS, 200));
        steady.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        shaky.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        steady.knockback(0.4, 1.0, 0.0);
        shaky.knockback(0.4, 1.0, 0.0);
        double ratio = shaky.getDeltaMovement().horizontalDistance() / steady.getDeltaMovement().horizontalDistance();
        expect(helper, Math.abs(ratio - 1.1) < 1e-3, "Unsteadiness: knockback +10 %, ratio " + ratio);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ room

    @GameTest(template = EMPTY)
    public static void openDoorLeaks(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        stove(helper, ModBlocks.STEEL_FIREBOX.get(), true, ModBlocks.CLOSED_HEATER.get(), true);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.OPEN, true);
        helper.setBlock(new BlockPos(2, 2, 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(new BlockPos(2, 3, 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        BathGaugeBlockEntity gauge = gauge(helper);
        expect(helper, gauge.leaky() && gauge.scan() == null, "an open door must leak");
        helper.setBlock(new BlockPos(2, 2, 1), door.setValue(DoorBlock.OPEN, false).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(new BlockPos(2, 3, 1), door.setValue(DoorBlock.OPEN, false).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        gauge.rescan();
        expect(helper, !gauge.leaky() && gauge.scan() != null, "a closed door seals the room");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void secondGaugeIsIgnored(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        StoveBlockEntity stove = stove(helper, ModBlocks.STEEL_FIREBOX.get(), true, ModBlocks.CLOSED_HEATER.get(), true);
        BathGaugeBlockEntity first = gauge(helper);
        BlockPos otherPos = new BlockPos(2, 3, 4);
        helper.setBlock(otherPos, ModBlocks.BATH_GAUGE.get().defaultBlockState().setValue(BathGaugeBlock.FACING, Direction.NORTH));
        BathGaugeBlockEntity second = helper.getBlockEntity(otherPos);
        first.rescan();
        second.rescan();
        expect(helper, first.scan().duplicateGauge() != second.scan().duplicateGauge(), "exactly one gauge is the main one");
        BathGaugeBlockEntity main = first.scan().duplicateGauge() ? second : first;
        BathGaugeBlockEntity spare = main == first ? second : first;
        stove.setFuel(StoveBlockEntity.MAX_FUEL);
        for (int s = 0; s < 240; s++) {
            spare.simulate(1.0);
            stove.advanceSecond();
            main.simulate(1.0);
        }
        expect(helper, main.temperature() > spare.temperature() + 20,
                "the main gauge gets the heat: main " + main.temperature() + ", spare " + spare.temperature());
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ plunges

    /**
     * Basin with a 2x2 interior at x0..x0+1, z0..z0+1, y 2..3 (floor at y 1), thermometer at the rim on the west side.
     *
     * @return the thermometer position
     */
    private static BlockPos basin(GameTestHelper helper, int x0, int z0, Block wall) {
        for (int x = x0 - 1; x <= x0 + 2; x++) {
            for (int z = z0 - 1; z <= z0 + 2; z++) {
                for (int y = 1; y <= 3; y++) {
                    boolean inside = x >= x0 && x <= x0 + 1 && z >= z0 && z <= z0 + 1 && y >= 2;
                    helper.setBlock(new BlockPos(x, y, z), inside ? Blocks.AIR : wall);
                }
            }
        }
        BlockPos thermometer = new BlockPos(x0 - 1, 3, z0);
        helper.setBlock(thermometer, ModBlocks.PLUNGE_THERMOMETER.get().defaultBlockState().setValue(PlungeThermometerBlock.FACING, Direction.EAST));
        return thermometer;
    }

    private static void fill(GameTestHelper helper, int x0, int z0, boolean bench) {
        for (int x = x0; x <= x0 + 1; x++) {
            for (int z = z0; z <= z0 + 1; z++) {
                for (int y = 2; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
                }
            }
        }
        if (bench) {
            helper.setBlock(new BlockPos(x0, 2, z0), ModBlocks.PLUNGE_BENCH.get().defaultBlockState().setValue(PlungeBenchBlock.WATERLOGGED, true));
        }
    }

    private static void ticks(ServerPlayer player, int seconds) {
        for (int t = 0; t < seconds * 20; t++) {
            PlungeTracker.tick(player);
        }
    }

    @GameTest(template = EMPTY)
    public static void coldPlungeContrast(GameTestHelper helper) {
        shell(helper, Blocks.OAK_PLANKS);
        stove(helper, ModBlocks.STEEL_FIREBOX.get(), false, ModBlocks.CLOSED_HEATER.get(), true);
        BathGaugeBlockEntity gauge = gauge(helper);
        assess(gauge, 61, 41, 0);

        BlockPos thermometerPos = basin(helper, 8, 8, ModBlocks.PLUNGE_WALL_TILE.get());
        fill(helper, 8, 8, true);
        PlungeControllerBlockEntity plunge = helper.getBlockEntity(thermometerPos);
        plunge.rescan();
        expect(helper, plunge.kind() == PlungeKind.COLD, "water without a stove is a cold plunge, problem: "
                + (plunge.problem() == null ? "-" : plunge.problem().getString()));
        plunge.setTemperature(6.0);

        ServerPlayer warm = player(helper, new BlockPos(2, 2, 4), 0.0);
        BathSessionTracker.visit(warm, gauge);
        expect(helper, BathSessionTracker.isWarm(warm), "a steam lodge warms the player up");
        BlockPos bench = helper.absolutePos(new BlockPos(8, 2, 8));
        warm.moveTo(bench.getX() + 0.5, bench.getY() + 0.5, bench.getZ() + 0.5);
        ticks(warm, 15);
        expect(helper, PlungeTracker.current(warm) != null, "the player should be in the plunge");
        warm.moveTo(bench.getX() + 0.5, bench.getY() + 3.0, bench.getZ() - 3.0);
        PlungeTracker.tick(warm);
        // icy water (4-8 C) is one point: level 2
        expect(helper, warm.hasEffect(ModEffects.TEMPERING) && warm.hasEffect(ModEffects.HARDINESS)
                && !warm.hasEffect(ModEffects.FROSTPROOF), "contrast level 2: Tempering and Hardiness");

        ServerPlayer cold = player(helper, new BlockPos(8, 2, 8), 0.5);
        ticks(cold, 15);
        cold.moveTo(bench.getX() + 0.5, bench.getY() + 3.0, bench.getZ() - 3.0);
        PlungeTracker.tick(cold);
        expect(helper, !cold.hasEffect(ModEffects.TEMPERING), "no contrast without warming up");
        remove(helper, warm);
        remove(helper, cold);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void hotPlungeChestDeepOnBench(GameTestHelper helper) {
        BlockPos thermometerPos = basin(helper, 8, 4, ModBlocks.PLUNGE_WALL_WOOD.get());
        fill(helper, 8, 4, true);
        helper.setBlock(new BlockPos(10, 2, 4), ModBlocks.PLUNGE_STOVE.get().defaultBlockState().setValue(PlungeStoveBlock.FACING, Direction.EAST));
        PlungeControllerBlockEntity plunge = helper.getBlockEntity(thermometerPos);
        plunge.rescan();
        expect(helper, plunge.kind() == PlungeKind.HOT, "water, stove and bench make a hot plunge, problem: "
                + (plunge.problem() == null ? "-" : plunge.problem().getString()));
        plunge.setTemperature(40.0);
        plunge.setHerbs(true);
        int soak = BathConfig.HOT_SOAK_SECONDS.get() + 1;

        ServerPlayer standing = player(helper, new BlockPos(9, 2, 5), 0.0);
        ticks(standing, soak);
        expect(helper, !standing.hasEffect(ModEffects.BLISS), "standing on the floor the water covers the heart");

        ServerPlayer seated = player(helper, new BlockPos(8, 2, 4), 0.5);
        ticks(seated, soak);
        expect(helper, seated.hasEffect(ModEffects.BLISS) && seated.hasEffect(ModEffects.SUPPLENESS)
                && seated.hasEffect(ModEffects.FEATHERLIGHT), "herbs, ideal 40 C, wooden basin: level 3");
        remove(helper, standing);
        remove(helper, seated);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void hotPlungeStoveHeatsWater(GameTestHelper helper) {
        BlockPos thermometerPos = basin(helper, 8, 4, ModBlocks.PLUNGE_WALL_WOOD.get());
        fill(helper, 8, 4, true);
        BlockPos stovePos = new BlockPos(10, 2, 4);
        helper.setBlock(stovePos, ModBlocks.PLUNGE_STOVE.get().defaultBlockState().setValue(PlungeStoveBlock.FACING, Direction.EAST));
        PlungeStoveBlockEntity stove = helper.getBlockEntity(stovePos);
        stove.setFuel(PlungeStoveBlockEntity.MAX_FUEL);
        PlungeControllerBlockEntity plunge = helper.getBlockEntity(thermometerPos);
        plunge.rescan();
        for (int s = 0; s < 500; s++) {
            plunge.simulate(1.0);
            stove.burnSecond();
        }
        double t = plunge.temperature();
        expect(helper, t >= 38 && t <= 43, "an outside stove should bring the water to 38-42 C and hold it, got " + t);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void plungeProblems(GameTestHelper helper) {
        BlockPos thermometerPos = basin(helper, 8, 4, ModBlocks.PLUNGE_WALL_WOOD.get());
        PlungeControllerBlockEntity plunge = helper.getBlockEntity(thermometerPos);
        plunge.rescan();
        expect(helper, plunge.kind() == null, "an empty basin is no plunge");

        fill(helper, 8, 4, false);
        helper.setBlock(new BlockPos(10, 2, 4), ModBlocks.PLUNGE_STOVE.get().defaultBlockState().setValue(PlungeStoveBlock.FACING, Direction.EAST));
        plunge.rescan();
        expect(helper, plunge.kind() == null, "a hot plunge without a bench is refused");

        helper.setBlock(new BlockPos(11, 3, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(10, 3, 5), Blocks.GLASS);
        plunge.rescan();
        expect(helper, plunge.scan() != null && !plunge.scan().valid(), "a foreign block in the wall breaks the basin");

        BlockPos sawdustThermometer = basin(helper, 8, 8, ModBlocks.PLUNGE_WALL_WOOD.get());
        for (int x = 8; x <= 9; x++) {
            for (int z = 8; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 2, z), ModBlocks.SAWDUST.get());
                helper.setBlock(new BlockPos(x, 3, z), ModBlocks.SAWDUST.get());
            }
        }
        helper.setBlock(new BlockPos(10, 2, 8), ModBlocks.PLUNGE_STOVE.get().defaultBlockState().setValue(PlungeStoveBlock.FACING, Direction.EAST));
        PlungeControllerBlockEntity sawdust = helper.getBlockEntity(sawdustThermometer);
        sawdust.rescan();
        expect(helper, sawdust.kind() == PlungeKind.SAWDUST, "shavings and a stove make a sawdust plunge");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ data

    @GameTest(template = EMPTY)
    public static void dataIsLoaded(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        for (var item : com.radar.bathhouse.registry.ModItems.ITEMS.getEntries()) {
            expect(helper, server.getRecipeManager().byKey(item.getId()).isPresent(), "missing recipe for " + item.getId());
        }
        for (var block : ModBlocks.BLOCKS.getEntries()) {
            var key = block.get().getLootTable();
            expect(helper, server.reloadableRegistries().getLootTable(key) != LootTable.EMPTY, "missing loot table " + key.location());
        }
        expect(helper, helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .containsKey(com.radar.bathhouse.registry.ModDamageTypes.OVERHEAT), "missing overheat damage type");
        expect(helper, Blocks.OAK_PLANKS.defaultBlockState().is(ModTags.BATH_WOOD), "oak planks are wood");
        expect(helper, Blocks.CALCITE.defaultBlockState().is(ModTags.BATH_MARBLE), "calcite is marble");
        expect(helper, StoneQuality.of(new ItemStack(Items.BASALT)) == 3, "basalt is an excellent heater stone");
        expect(helper, StoneQuality.of(new ItemStack(Items.COBBLESTONE)) == 1, "cobblestone is a poor heater stone");
        helper.succeed();
    }
}
