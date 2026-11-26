package com.radar.bathhouse.client;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.item.LadleItem;
import com.radar.bathhouse.registry.ModItems;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = Bathhouse.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(ModItems.LADLE.get(), Bathhouse.id("filled"),
                (stack, level, entity, seed) -> LadleItem.water(stack) <= 0 ? 0.0F : LadleItem.isHot(stack) ? 1.0F : 0.5F));
    }

}
