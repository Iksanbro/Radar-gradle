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
        event.registerBlockEntityRenderer(FcsRegistry.GUN_ROTATOR_BE.get(), GunRotatorRenderer::new);
        event.registerBlockEntityRenderer(FcsRegistry.GUN_DRIVE_BE.get(), GunDriveRenderer::new);
    }

    /**
     * Взгляд на экран монитора: рамку выделения блока не рисовать - у монитора свой курсор, а рамка одного блока
     * выдаёт стыки стены экрана. С боков и сзади рамка обычная.
     */
    /**
     * Нажатие колеса мыши (клавиша «выбрать блок») по отметке на экране монитора: исключить цель из огня
     * вычислителей сети или вернуть её. Мимо отметок - обычный выбор блока.
     */
    @SubscribeEvent
    public static void pick(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isPickBlock()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        BlockState st = mc.level.getBlockState(hit.getBlockPos());
        if (!(st.getBlock() instanceof RadarMonitorBlock) || hit.getDirection() != st.getValue(RadarMonitorBlock.FACING)) return;
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof dev.radarfcs.block.RadarMonitorBlockEntity m)) return;
        var master = m.master();
        String id = RadarMonitorRenderer.hoverId;
        if (id == null || !master.getBlockPos().equals(RadarMonitorRenderer.hoverMonitor)) return;
        for (var b : master.blips()) {
            if (!b.id().equals(id)) continue;
            if ((b.flags() & dev.radarfcs.block.RadarMonitorBlockEntity.FLAG_FRIENDLY) == 0) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new dev.radarfcs.net.MonitorCommandPayload(
                        master.getBlockPos(), dev.radarfcs.net.MonitorCommandPayload.EXCLUDE, 0, id));
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
    }

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
