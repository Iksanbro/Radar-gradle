package dev.radarfcs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.CameraBlockEntity;
import dev.radarfcs.compat.SableCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Поворотная часть камеры: цилиндр крутится по курсу (вокруг вертикали), шар-голова - по курсу и наклону, туда, куда
 * смотрит камера (последний взгляд игрока). Модели заданы «объективом на север»: цилиндр - y 9..13, центр шара - (8, 5, 8);
 * у камеры на полу цилиндр опущен вниз, шар поднят наверх.
 */
public class CameraRenderer extends SafeBlockEntityRenderer<CameraBlockEntity> {
    public static final ModelResourceLocation PAN = model("camera_pan");
    public static final ModelResourceLocation HEAD = model("camera_head");

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "block/" + name));
    }

    public CameraRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    protected void renderSafe(CameraBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        float[] v = be.renderView(partialTicks);
        // взгляд в мире -> в координатах блока (камера на корабле Sable поворачивается вместе с ним)
        Vec3 dir = Vec3.directionFromRotation(v[1], v[0]);
        if (be.getLevel() != null) dir = SableCompat.dirToLocal(SableCompat.containing(be.getLevel(), be.getBlockPos()), dir);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dir.y))));
        boolean floor = be.facing() == Direction.UP;
        float panShift = floor ? -6f / 16 : 0, headY = (floor ? 11f : 5f) / 16;

        ms.pushPose();
        ms.translate(0.5, panShift, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        ms.translate(-0.5, 0, -0.5);
        draw(be, PAN, ms, buffer, light, overlay);
        ms.popPose();

        ms.pushPose();
        ms.translate(0.5, headY, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        ms.mulPose(Axis.XP.rotationDegrees(-pitch));
        ms.translate(-0.5, -5f / 16, -0.5);
        draw(be, HEAD, ms, buffer, light, overlay);
        ms.popPose();
    }

    private static void draw(CameraBlockEntity be, ModelResourceLocation id, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        BakedModel model = Minecraft.getInstance().getModelManager().getModel(id);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(ms.last(), buffer.getBuffer(RenderType.cutout()),
                be.getBlockState(), model, 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.cutout());
    }
}
