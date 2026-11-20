package dev.radarfcs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.radarfcs.block.AimControllerBlockEntity;
import dev.radarfcs.compat.CbcMount;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Валы контроллера наводки: входной (сзади) крутится как кинетическая сеть, выходной (к станку) повёрнут
 * на текущий угол станка - видно, куда и насколько контроллер повернул пушку.
 */
public class AimControllerRenderer extends SafeBlockEntityRenderer<AimControllerBlockEntity> {

    public AimControllerRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    protected void renderSafe(AimControllerBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        BlockState state = be.getBlockState();
        Direction toMount = be.toMount();
        Direction.Axis axis = toMount.getAxis();
        VertexConsumer vb = buffer.getBuffer(RenderType.solid());

        float in = KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), axis);
        KineticBlockEntityRenderer.kineticRotationTransform(
                CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, toMount.getOpposite()), be, axis, in, light)
                .renderInto(ms, vb);

        CbcMount mount = be.mount();
        float out = 0;
        if (mount != null) {
            double deg = be.isPitch() ? mount.pitch() : mount.yaw();
            out = (float) Math.toRadians(deg) * (toMount.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1);
        }
        KineticBlockEntityRenderer.kineticRotationTransform(
                CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, toMount), be, axis, out, light)
                .renderInto(ms, vb);
    }
}
