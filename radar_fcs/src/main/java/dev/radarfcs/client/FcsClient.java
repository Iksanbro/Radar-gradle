package dev.radarfcs.client;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.IffTransponderBlock;
import dev.radarfcs.block.RadarMonitorBlock;
import dev.radarfcs.registry.FcsRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

/** Клиент: цвет канала транспондера, экран монитора, валы контроллеров наводки. */
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
        event.registerBlockEntityRenderer(FcsRegistry.AIM_CONTROLLER_BE.get(), AimControllerRenderer::new);
    }

    /**
     * Взгляд на экран монитора: рамку выделения блока не рисовать - у монитора свой курсор, а рамка одного блока
     * выдаёт стыки стены экрана. С боков и сзади рамка обычная.
     */
    @SubscribeEvent
    public static void highlight(RenderHighlightEvent.Block event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BlockHitResult hit = event.getTarget();
        BlockState st = mc.level.getBlockState(hit.getBlockPos());
        if (st.getBlock() instanceof RadarMonitorBlock && hit.getDirection() == st.getValue(RadarMonitorBlock.FACING)) {
            event.setCanceled(true);
        }
    }
}
