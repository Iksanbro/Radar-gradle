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
    /** Железная шестерня поворотного подшипника Aeronautics (их модель с нашей текстурой, при Aeronautics) или null. */
    @org.jetbrains.annotations.Nullable private static dev.engine_room.flywheel.lib.model.baked.PartialModel ironCog;

    /** Модели частей - заранее, при создании мода (до загрузки моделей). */
    public static void init() {
        // их железная шестерня с нашей перекрашенной текстурой (модель - в пакете AeroBearingLook)
        if (dev.radarfcs.compat.Mods.SIMULATED) ironCog = dev.engine_room.flywheel.lib.model.baked.PartialModel.of(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(dev.radarfcs.FcsMod.MODID, "block/gun_bearing_cog"));
    }

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
        var cogModel = ironCog;
        // пакет с видом Aeronautics не собрался - модели шестерни нет: рисовать шестерню Create
        if (cogModel != null && cogModel.get() == net.minecraft.client.Minecraft.getInstance().getModelManager().getMissingModel()) cogModel = null;
        var cog = cogModel != null
                // кольцо Aeronautics - между основанием и верхней плитой их модели (на 2 пикселя выше центра)
                ? CachedBuffers.partialFacingVertical(cogModel, be.getBlockState(), f)
                        .translate(f.getStepX() * 2 / 16f, f.getStepY() * 2 / 16f, f.getStepZ() * 2 / 16f)
                : CachedBuffers.partialFacingVertical(AllPartialModels.SHAFTLESS_COGWHEEL, be.getBlockState(), f);
        KineticBlockEntityRenderer.kineticRotationTransform(cog, be, axis, ring, light).renderInto(ms, solid);
    }
}
