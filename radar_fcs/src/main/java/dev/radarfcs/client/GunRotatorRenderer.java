package dev.radarfcs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.radarfcs.block.GunRotatorBlockEntity;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** Вал входа вращателя орудия (сзади) крутится вместе с кинетической сетью. */
public class GunRotatorRenderer extends SafeBlockEntityRenderer<GunRotatorBlockEntity> {
    public GunRotatorRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    protected void renderSafe(GunRotatorBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        Direction f = be.facing();
        float in = KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), f.getAxis());
        KineticBlockEntityRenderer.kineticRotationTransform(
                CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, be.getBlockState(), f.getOpposite()), be, f.getAxis(), in, light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));
    }
}
