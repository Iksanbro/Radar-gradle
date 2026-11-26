package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.climate.BathType;
import com.radar.bathhouse.item.BathToolItem;
import com.radar.bathhouse.item.LadleItem;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Bathhouse.MOD_ID);

    static {
        for (DeferredBlock<?> block : ModBlocks.BLOCKS.getEntries().stream()
                .map(holder -> (DeferredBlock<?>) holder).toList()) {
            ITEMS.registerSimpleBlockItem(block);
        }
    }

    public static final DeferredItem<LadleItem> LADLE = ITEMS.register("ladle",
            () -> new LadleItem(new Item.Properties().stacksTo(1)));
    /** Leafy broom for the steam lodge. Useless (and painful) in the dry heat of an ember room. */
    public static final DeferredItem<BathToolItem> BATH_BROOM = ITEMS.register("bath_broom",
            () -> new BathToolItem(new Item.Properties().durability(64), BathType.STEAM, BathType.EMBER,
                    SoundEvents.AZALEA_LEAVES_HIT, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BIRCH_LEAVES.defaultBlockState())));
    /** Coarse scrubbing mitt for the mist hall. */
    public static final DeferredItem<BathToolItem> SCRUB_MITT = ITEMS.register("scrub_mitt",
            () -> new BathToolItem(new Item.Properties().durability(96), BathType.MIST, null,
                    SoundEvents.WOOL_HIT, ParticleTypes.BUBBLE_POP));

    private ModItems() {
    }
}
