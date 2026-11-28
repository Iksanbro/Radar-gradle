package dev.radarfcs.gametest;

import com.simibubi.create.foundation.utility.BlockHelper;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.CameraBlockEntity;
import dev.radarfcs.block.GunLinkBlockEntity;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.compat.Mods;
import dev.radarfcs.compat.rocket.HeatNoseBlockEntity;
import dev.radarfcs.compat.rocket.NetNoseBlockEntity;
import dev.radarfcs.compat.rocket.RocketNoses;
import dev.radarfcs.core.EngageSettings.Category;
import dev.radarfcs.registry.FcsRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Перенос настроек: носы ракет снимаются ключом Create с настройками, ненастроенные носы перенимают настройки
 * настроенного крафтом; передатчики, камера и носы ставятся пушкой схематик Create со своей радарной сетью.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class CopyGameTests {
    private static final String ARENA = "arena";

    @AfterBatch(batch = "copy_nose") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }
    @AfterBatch(batch = "copy_schematic") public static void a2(ServerLevel l) { FcsGameTests.cleanup(l); }

    private static CompoundTag data(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? new CompoundTag() : d.copyTag();
    }

    /** Shift+ПКМ ключом Create по блоку игроком в выживании - что попало в инвентарь (предмет блока). */
    private static ItemStack wrench(GameTestHelper h, BlockPos rel, Item expect) {
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("create:wrench"))));
        p.setShiftKeyDown(true);
        LinkGameTests.use(h, p, rel, Direction.UP);
        if (!h.getLevel().getBlockState(h.absolutePos(rel)).isAir()) h.fail("wrench did not pick the block up at " + rel);
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(expect)) return s.copy();
        }
        h.fail("wrench pickup gave no " + expect);
        return ItemStack.EMPTY;
    }

    private static Optional<RecipeHolder<CraftingRecipe>> craft(GameTestHelper h, ItemStack... grid) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) items.add(i < grid.length ? grid[i] : ItemStack.EMPTY);
        return h.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, items), h.getLevel());
    }

    /**
     * Программируемый нос (сеть, режим «по назначенной», допустимая цель - корабли) и тепловая головка (только корабли)
     * снимаются ключом с настройками; настроенный нос + 2 ненастроенных в сетке - 2 настроенных, настроенный остаётся;
     * два настроенных, носы разных видов или один нос - не крафт. Копия, поставленная игроком, - в той же сети.
     */
    @GameTest(template = ARENA, batch = "copy_nose", timeoutTicks = 100)
    public static void noseSettingsKeptByWrenchAndCopiedByCrafting(GameTestHelper h) {
        if (!Mods.ENHANCED_SHELLS) {
            h.succeed();
            return;
        }
        FcsGameTests.floor(h);
        UUID net = UUID.randomUUID();
        int ships = HeatNoseBlockEntity.bit(Category.SHIPS);
        BlockPos netPos = new BlockPos(4, 1, 4), heatPos = new BlockPos(8, 1, 4);
        h.setBlock(netPos, RocketNoses.NET.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        h.setBlock(heatPos, RocketNoses.HEAT.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(netPos)) instanceof NetNoseBlockEntity nn)
                || !(h.getLevel().getBlockEntity(h.absolutePos(heatPos)) instanceof HeatNoseBlockEntity hn)) {
            h.fail("noses not placed");
            return;
        }
        nn.setNetwork(net);
        nn.apply(NetNoseBlockEntity.Mode.AUTO_DESIGNATED, Category.SHIPS.ordinal());
        hn.setCategories(ships);

        // 1. ключ: нос - в инвентарь с настройками
        ItemStack netItem = wrench(h, netPos, RocketNoses.NET_ITEM.get());
        ItemStack heatItem = wrench(h, heatPos, RocketNoses.HEAT_ITEM.get());
        CompoundTag nd = data(netItem), hd = data(heatItem);
        if (!nd.hasUUID("RadarNetwork") || !net.equals(nd.getUUID("RadarNetwork"))) h.fail("wrenched network nose lost its network: " + nd);
        if (!"AUTO_DESIGNATED".equals(nd.getString("Mode")) || nd.getInt("Trigger") != Category.SHIPS.ordinal()) {
            h.fail("wrenched network nose lost its mode: " + nd);
        }
        if (hd.getInt("Targets") != ships) h.fail("wrenched heat seeker lost its targets: " + hd);

        // 2. крафт: настроенный + 2 ненастроенных
        ItemStack blankNet = new ItemStack(RocketNoses.NET_ITEM.get());
        ItemStack blankHeat = new ItemStack(RocketNoses.HEAT_ITEM.get());
        ItemStack[] grid = {blankNet.copy(), netItem.copyWithCount(1), ItemStack.EMPTY, blankNet.copy()};
        Optional<RecipeHolder<CraftingRecipe>> r = craft(h, grid);
        if (r.isEmpty()) {
            h.fail("no copy recipe for a configured nose and two blank ones");
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) items.add(i < grid.length ? grid[i] : ItemStack.EMPTY);
        CraftingInput in = CraftingInput.of(3, 3, items);
        ItemStack out = r.get().value().assemble(in, h.getLevel().registryAccess());
        if (!out.is(RocketNoses.NET_ITEM.get()) || out.getCount() != 2) h.fail("copy recipe gave " + out);
        if (!data(out).equals(nd)) h.fail("copies have other settings: " + data(out) + " vs " + nd);
        NonNullList<ItemStack> rest = r.get().value().getRemainingItems(in);
        long kept = rest.stream().filter(s -> s.is(RocketNoses.NET_ITEM.get()) && data(s).equals(nd)).count();
        if (kept != 1) h.fail("configured nose did not stay in the grid: " + rest);
        // тепловая: настроенная + 1 ненастроенная
        Optional<RecipeHolder<CraftingRecipe>> rh = craft(h, heatItem.copyWithCount(1), blankHeat.copy());
        if (rh.isEmpty()) {
            h.fail("no copy recipe for heat seekers");
            return;
        }
        // не крафт: два настроенных, разные носы, один нос
        if (craft(h, netItem.copyWithCount(1), netItem.copyWithCount(1), blankNet.copy()).isPresent()) h.fail("two configured noses crafted");
        if (craft(h, netItem.copyWithCount(1), blankHeat.copy()).isPresent()) h.fail("different noses crafted");
        if (craft(h, netItem.copyWithCount(1)).isPresent()) h.fail("a single nose crafted");

        // 3. копия, поставленная игроком, - в той же сети и режиме
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, out.copyWithCount(1));
        BlockPos placed = new BlockPos(12, 0, 4);
        LinkGameTests.use(h, p, placed, Direction.UP);
        FcsGameTests.CREATED.add(h.absolutePos(placed.above()));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(placed.above())) instanceof NetNoseBlockEntity copy)) {
            h.fail("crafted copy was not placed");
            return;
        }
        if (!net.equals(copy.network()) || copy.mode() != NetNoseBlockEntity.Mode.AUTO_DESIGNATED || copy.trigger() != Category.SHIPS.ordinal()) {
            h.fail("placed copy has other settings: " + copy.network() + " " + copy.mode() + " " + copy.trigger());
        }
        FcsMod.LOG.info("[radar_fcs test] copy_nose: wrench keeps settings, crafting copies them ({} copies)", out.getCount());
        h.succeed();
    }

    /** Данные блока для схематики Create - и печать его в другом месте, как пушкой схематик. */
    private static BlockEntity print(GameTestHelper h, BlockPos from, BlockPos to) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(from), b = h.absolutePos(to);
        BlockState st = level.getBlockState(a);
        CompoundTag data = BlockHelper.prepareBlockEntityData(level, st, level.getBlockEntity(a));
        if (data == null) {
            h.fail("schematic keeps no data for " + st.getBlock());
            return null;
        }
        BlockHelper.placeSchematicBlock(level, st, b, new ItemStack(st.getBlock().asItem()), data);
        FcsGameTests.CREATED.add(b);
        return level.getBlockEntity(b);
    }

    /**
     * Пушка схематик Create: передатчик радарной сети, передатчик орудийной сети, камера (имя и сеть) и программируемый
     * нос печатаются со своей сетью; служебное (счётчики, загрузка сети) в схематику не попадает.
     */
    @GameTest(template = ARENA, batch = "copy_schematic", timeoutTicks = 100)
    public static void schematicKeepsNetworks(GameTestHelper h) {
        FcsGameTests.floor(h);
        UUID radar = UUID.randomUUID(), guns = UUID.randomUUID();
        BlockPos fcs = new BlockPos(4, 1, 4), linkAt = fcs.above();
        h.setBlock(fcs, FcsRegistry.FIRE_CONTROL.get().defaultBlockState());
        h.setBlock(linkAt, FcsRegistry.RADAR_LINK.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        BlockPos fcs2 = new BlockPos(4, 1, 10);
        h.setBlock(fcs2, FcsRegistry.FIRE_CONTROL.get().defaultBlockState());
        FcsGameTests.CREATED.add(h.absolutePos(fcs));
        FcsGameTests.CREATED.add(h.absolutePos(fcs2));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(linkAt)) instanceof RadarLinkBlockEntity link)) {
            h.fail("radar link not placed");
            return;
        }
        link.setNetwork(radar);
        CompoundTag safe = BlockHelper.prepareBlockEntityData(h.getLevel(), link.getBlockState(), link);
        if (safe == null || safe.contains("Usage") || safe.contains("Counts")) h.fail("schematic data of the radar link is not just the network: " + safe);
        if (!(print(h, linkAt, fcs2.above()) instanceof RadarLinkBlockEntity link2) || !radar.equals(link2.network())) {
            h.fail("printed radar link is not in the network");
            return;
        }

        BlockPos gunAt = new BlockPos(8, 1, 4);
        h.setBlock(gunAt, FcsRegistry.GUN_LINK.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(gunAt)) instanceof GunLinkBlockEntity gun)) {
            h.fail("gun link not placed");
            return;
        }
        gun.setNetwork(guns);
        if (!(print(h, gunAt, new BlockPos(8, 1, 10)) instanceof GunLinkBlockEntity gun2) || !guns.equals(gun2.network())) {
            h.fail("printed gun link is not in the gun network");
            return;
        }

        BlockPos camAt = new BlockPos(12, 1, 4);
        h.setBlock(camAt, FcsRegistry.CAMERA.get().defaultBlockState());
        if (!(h.getLevel().getBlockEntity(h.absolutePos(camAt)) instanceof CameraBlockEntity cam)) {
            h.fail("camera not placed");
            return;
        }
        cam.setNetwork(radar);
        cam.setName("Bow");
        if (!(print(h, camAt, new BlockPos(12, 1, 10)) instanceof CameraBlockEntity cam2) || !radar.equals(cam2.network()) || !"Bow".equals(cam2.name())) {
            h.fail("printed camera lost its name or network");
            return;
        }

        if (Mods.ENHANCED_SHELLS) {
            BlockPos noseAt = new BlockPos(16, 1, 4);
            h.setBlock(noseAt, RocketNoses.NET.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
            h.setBlock(new BlockPos(20, 1, 4), RocketNoses.HEAT.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
            if (!(h.getLevel().getBlockEntity(h.absolutePos(noseAt)) instanceof NetNoseBlockEntity nose)
                    || !(h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(20, 1, 4))) instanceof HeatNoseBlockEntity heat)) {
                h.fail("noses not placed");
                return;
            }
            nose.setNetwork(radar);
            nose.apply(NetNoseBlockEntity.Mode.AUTO, -1);
            heat.setCategories(HeatNoseBlockEntity.bit(Category.SHIPS));
            if (!(print(h, noseAt, new BlockPos(16, 1, 10)) instanceof NetNoseBlockEntity nose2) || !radar.equals(nose2.network())
                    || nose2.mode() != NetNoseBlockEntity.Mode.AUTO) {
                h.fail("printed network nose lost its network or mode");
                return;
            }
            if (!(print(h, new BlockPos(20, 1, 4), new BlockPos(20, 1, 10)) instanceof HeatNoseBlockEntity heat2)
                    || heat2.categories() != HeatNoseBlockEntity.bit(Category.SHIPS)) {
                h.fail("printed heat seeker lost its targets");
                return;
            }
        }
        FcsMod.LOG.info("[radar_fcs test] copy_schematic: links, camera and noses printed with their networks");
        h.succeed();
    }
}
