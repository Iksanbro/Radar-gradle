package dev.radarfcs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.radarfcs.block.GunBearingBlockEntity;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/**
 * Подшипник орудия: вал насквозь (оба полувала крутятся с его кинетической сетью) и кольцо-шестерня между основанием
 * и верхней плитой - крутится в зацеплении с шестерней рядом (её скорость, навстречу, со сдвигом зубьев по позиции).
 */
public class GunBearingRenderer extends SafeBlockEntityRenderer<GunBearingBlockEntity> {
    public GunBearingRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    protected void renderSafe(GunBearingBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        Direction f = be.facing();
        Direction.Axis axis = f.getAxis();
        var solid = buffer.getBuffer(RenderType.solid());
        float shaft = KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), axis);
        for (Direction d : new Direction[]{f, f.getOpposite()}) {
            KineticBlockEntityRenderer.kineticRotationTransform(
                    CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, be.getBlockState(), d), be, axis, shaft, light)
                    .renderInto(ms, solid);
        }
        float time = AnimationTickHolder.getRenderTime(be.getLevel());
        float offset = KineticBlockEntityRenderer.getRotationOffsetForPosition(be, be.getBlockPos(), axis);
        float ring = ((time * be.ringSpeed() * 3f / 10 + offset) % 360) / 180 * (float) Math.PI;
        KineticBlockEntityRenderer.kineticRotationTransform(
                CachedBuffers.partialFacingVertical(AllPartialModels.SHAFTLESS_COGWHEEL, be.getBlockState(), f), be, axis, ring, light)
                .renderInto(ms, solid);
    }
}
