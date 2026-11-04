package dev.radarfcs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.radarfcs.block.GunDriveBlockEntity;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** Валы привода орудия: каждый полувал крутится со своей скоростью (выход = вход x выход привода). */
public class GunDriveRenderer extends SafeBlockEntityRenderer<GunDriveBlockEntity> {
    public GunDriveRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    protected void renderSafe(GunDriveBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        Direction.Axis axis = be.facing().getAxis();
        float time = AnimationTickHolder.getRenderTime(be.getLevel());
        float offset = KineticBlockEntityRenderer.getRotationOffsetForPosition(be, be.getBlockPos(), axis);
        for (Direction d : new Direction[]{be.facing(), be.facing().getOpposite()}) {
            float speed = be.getSpeed() * be.getRotationSpeedModifier(d);
            float angle = ((time * speed * 3f / 10 + offset) % 360) / 180 * (float) Math.PI;
            KineticBlockEntityRenderer.kineticRotationTransform(
                    CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, be.getBlockState(), d), be, axis, angle, light)
                    .renderInto(ms, buffer.getBuffer(RenderType.solid()));
        }
    }
}
