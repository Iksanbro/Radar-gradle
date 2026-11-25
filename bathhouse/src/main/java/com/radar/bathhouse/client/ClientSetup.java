package com.radar.bathhouse.client;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.item.LadleItem;
import com.radar.bathhouse.registry.ModItems;
import com.radar.bathhouse.registry.ModBlocks;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

@EventBusSubscriber(modid = Bathhouse.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(ModItems.LADLE.get(), Bathhouse.id("filled"),
                (stack, level, entity, seed) -> LadleItem.water(stack) > 0 ? 1.0F : 0.0F));
    }

    /** Water in the plunge tubs takes the biome's water colour. */
    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> tintIndex != 0 ? -1
                        : level != null && pos != null ? BiomeColors.getAverageWaterColor(level, pos) : 0x3F76E4,
                ModBlocks.COLD_PLUNGE.get(), ModBlocks.HOT_PLUNGE.get());
    }
}
