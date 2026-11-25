package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.climate.BathType;
import com.radar.bathhouse.item.BathToolItem;
import com.radar.bathhouse.item.LadleItem;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Bathhouse.MOD_ID);

    public static final DeferredItem<BlockItem> INNER_HEATER = ITEMS.registerSimpleBlockItem(ModBlocks.INNER_HEATER);
    public static final DeferredItem<BlockItem> OUTER_HEATER = ITEMS.registerSimpleBlockItem(ModBlocks.OUTER_HEATER);
    public static final DeferredItem<BlockItem> BRICK_STOVE = ITEMS.registerSimpleBlockItem(ModBlocks.BRICK_STOVE);
    public static final DeferredItem<BlockItem> IRON_STOVE = ITEMS.registerSimpleBlockItem(ModBlocks.IRON_STOVE);
    public static final DeferredItem<BlockItem> ELECTRIC_HEATER = ITEMS.registerSimpleBlockItem(ModBlocks.ELECTRIC_HEATER);
    public static final DeferredItem<BlockItem> STEAM_GENERATOR = ITEMS.registerSimpleBlockItem(ModBlocks.STEAM_GENERATOR);
    public static final DeferredItem<BlockItem> WARM_LOUNGER = ITEMS.registerSimpleBlockItem(ModBlocks.WARM_LOUNGER);
    public static final DeferredItem<BlockItem> COLD_PLUNGE = ITEMS.registerSimpleBlockItem(ModBlocks.COLD_PLUNGE);
    public static final DeferredItem<BlockItem> HOT_PLUNGE = ITEMS.registerSimpleBlockItem(ModBlocks.HOT_PLUNGE);
    public static final DeferredItem<BlockItem> SAWDUST_PLUNGE = ITEMS.registerSimpleBlockItem(ModBlocks.SAWDUST_PLUNGE);
    public static final DeferredItem<BlockItem> BATH_GAUGE = ITEMS.registerSimpleBlockItem(ModBlocks.BATH_GAUGE);
    public static final DeferredItem<BlockItem> CHIMNEY = ITEMS.registerSimpleBlockItem(ModBlocks.CHIMNEY);
    public static final DeferredItem<BlockItem> SMOKE_VENT = ITEMS.registerSimpleBlockItem(ModBlocks.SMOKE_VENT);

    public static final DeferredItem<LadleItem> LADLE = ITEMS.register("ladle",
            () -> new LadleItem(new Item.Properties().stacksTo(1)));
    /** Leafy broom for the steam lodge. Useless (and painful) in the dry heat of an ember room. */
    public static final DeferredItem<BathToolItem> BATH_BROOM = ITEMS.register("bath_broom",
            () -> new BathToolItem(new Item.Properties().durability(64), BathType.STEAM, BathType.EMBER,
                    SoundEvents.AZALEA_LEAVES_HIT, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BIRCH_LEAVES.defaultBlockState())));
    /** Coarse scrubbing mitt for the mist bath. */
    public static final DeferredItem<BathToolItem> SCRUB_MITT = ITEMS.register("scrub_mitt",
            () -> new BathToolItem(new Item.Properties().durability(96), BathType.MIST, null,
                    SoundEvents.WOOL_HIT, ParticleTypes.BUBBLE_POP));
    /** Wood shavings for the sawdust plunge. */
    public static final DeferredItem<Item> SAWDUST = ITEMS.registerSimpleItem("sawdust");

    private ModItems() {
    }
}
