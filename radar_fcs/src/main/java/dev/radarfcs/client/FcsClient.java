package dev.radarfcs.client;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.IffTransponderBlock;
import dev.radarfcs.registry.FcsRegistry;
import net.minecraft.world.item.DyeColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/** Клиент: полоса транспондера окрашена в цвет канала. */
@EventBusSubscriber(modid = FcsMod.MODID, value = Dist.CLIENT)
public final class FcsClient {
    private FcsClient() {}

    @SubscribeEvent
    public static void blockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> tint == 0 && state.hasProperty(IffTransponderBlock.CHANNEL)
                ? state.getValue(IffTransponderBlock.CHANNEL).getTextureDiffuseColor() : -1, FcsRegistry.IFF_TRANSPONDER.get());
    }

    @SubscribeEvent
    public static void itemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? DyeColor.WHITE.getTextureDiffuseColor() : -1, FcsRegistry.IFF_TRANSPONDER_ITEM.get());
    }
}
