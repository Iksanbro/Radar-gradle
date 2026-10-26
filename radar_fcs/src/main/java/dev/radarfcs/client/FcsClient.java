package dev.radarfcs.client;

import com.simibubi.create.content.contraptions.bearing.BearingRenderer;
import com.simibubi.create.content.contraptions.bearing.BearingVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.IffTransponderBlock;
import dev.radarfcs.registry.FcsRegistry;
import net.minecraft.world.item.DyeColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/** Клиент: цвет канала транспондера, экран монитора, вращающийся верх радарного подшипника. */
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

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(FcsRegistry.RADAR_MONITOR_BE.get(), RadarMonitorRenderer::new);
        event.registerBlockEntityRenderer(FcsRegistry.RADAR_BEARING_BE.get(), BearingRenderer::new);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        // как у подшипников Create: с Flywheel верх подшипника рисует визуал, без него - BearingRenderer
        SimpleBlockEntityVisualizer.builder(FcsRegistry.RADAR_BEARING_BE.get())
                .factory(BearingVisual::new)
                .skipVanillaRender(be -> true)
                .apply();
    }
}
