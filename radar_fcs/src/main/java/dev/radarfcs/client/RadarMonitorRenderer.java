package dev.radarfcs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.block.RadarMonitorBlockEntity.Blip;
import dev.radarfcs.block.RadarMonitorBlockEntity.Site;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * Экран радарного монитора: круговая развёртка. Вверх на экране - направление от зрителя
 * (куда он смотрит, стоя перед монитором), вправо - вправо от зрителя.
 */
public class RadarMonitorRenderer implements BlockEntityRenderer<RadarMonitorBlockEntity> {
    private static final int BG = 0xF0060D08;
    private static final int DISK = 0xF00B2412;
    private static final int RING = 0xB040C060;
    private static final int GRID = 0x6040C060;
    private static final int SWEEP = 0x60FF60;
    private static final int SITE = 0xFF60FF80;
    private static final int HOSTILE = 0xFFFFA020;
    private static final int ENGAGED = 0xFFFF3030;
    private static final int DESIGNATED = 0xFFFFFF40;
    private static final int TEXT = 0xFF70FF90;

    private final Font font;

    public RadarMonitorRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.getFont();
    }

    @Override
    public void render(RadarMonitorBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffers, int light, int overlay) {
        if (!be.isMaster() || be.getLevel() == null) return;
        Direction f = be.facing();
        Direction right = f.getCounterClockWise();
        int w = be.width(), h = be.height();
        ps.pushPose();
        ps.translate(0.5, 0.5, 0.5);
        // локально: X - вправо от зрителя, Y - вверх, Z - к зрителю; центр ведущего блока в (0,0,0)
        ps.mulPose(Axis.YP.rotationDegrees(-f.toYRot()));
        ps.translate(0, 0, 0.5 + 0.003);
        Matrix4f m = ps.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());

        float e = 1 / 16f;
        rect(vc, m, -0.5f + e, -0.5f + e, w - 0.5f - e, h - 0.5f - e, 0, BG);
        float cx = (w - 1) / 2f, cy = (h - 1) / 2f;
        float r = (float) (Math.min(w, h) / 2.0 * RadarMonitorBlockEntity.PPI_FRACTION);
        float t = Math.max(0.006f, r * 0.016f);
        disk(vc, m, cx, cy, r, 0.0004f, DISK);
        for (int k = 1; k <= 3; k++) ring(vc, m, cx, cy, r * k / 3f, t, 0.0008f, RING);
        rect(vc, m, cx - r, cy - t / 3, cx + r, cy + t / 3, 0.0008f, GRID);
        rect(vc, m, cx - t / 3, cy - r, cx + t / 3, cy + r, 0.0008f, GRID);

        // развёртка: бегущий сектор с затуханием
        float time = be.getLevel().getGameTime() + partialTick;
        float a0 = (time * 4f) % 360f;
        int seg = 16;
        for (int i = 0; i < seg; i++) {
            float from = a0 - 36f * (i + 1) / seg, to = a0 - 36f * i / seg;
            int alpha = (int) (110 * (1 - i / (float) seg));
            wedge(vc, m, cx, cy, r, from, to, 0.0012f, (alpha << 24) | SWEEP);
        }

        float range = Math.max(1f, be.displayRange());
        float s = Math.max(0.012f, r * 0.045f);
        for (Site site : be.sites()) {
            float[] p = toScreen(site.dx(), site.dz(), f, right, range, r, cx, cy);
            if (p == null) continue;
            rect(vc, m, p[0] - s * 0.6f, p[1] - s * 0.6f, p[0] + s * 0.6f, p[1] + s * 0.6f, 0.0016f, SITE);
        }
        // центр - сам монитор
        rect(vc, m, cx - s * 0.9f, cy - s * 0.25f, cx + s * 0.9f, cy + s * 0.25f, 0.0016f, 0xFFFFFFFF);
        rect(vc, m, cx - s * 0.25f, cy - s * 0.9f, cx + s * 0.25f, cy + s * 0.9f, 0.0016f, 0xFFFFFFFF);
        int count = 0;
        for (Blip b : be.blips()) {
            float[] p = toScreen(b.dx(), b.dz(), f, right, range, r, cx, cy);
            if (p == null) continue;
            count++;
            boolean ship = (b.flags() & RadarMonitorBlockEntity.FLAG_SHIP) != 0;
            float bs = ship ? s * 1.5f : s;
            int col = (b.flags() & RadarMonitorBlockEntity.FLAG_ENGAGED) != 0 ? ENGAGED : HOSTILE;
            rect(vc, m, p[0] - bs, p[1] - bs, p[0] + bs, p[1] + bs, 0.002f, col);
            if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) {
                float o = bs * 2.2f, th = bs * 0.45f;
                frame(vc, m, p[0] - o, p[1] - o, p[0] + o, p[1] + o, th, 0.0024f, DESIGNATED);
            }
        }

        // цвет канала - полоска в правом нижнем углу
        int dye = be.channel().getTextureDiffuseColor();
        rect(vc, m, w - 0.5f - e - 0.12f, -0.5f + e + 0.02f, w - 0.5f - e - 0.02f, -0.5f + e + 0.05f, 0.002f, 0xFF000000 | (dye & 0xFFFFFF));

        // подписи - последними: текст берёт из буферов свой тип рендера, и буфер квадов после него недействителен
        float scale = 0.0075f * (float) Math.sqrt(Math.min(w, h));
        ps.pushPose();
        ps.translate(-0.5f + e * 1.6f, h - 0.5f - e * 1.6f, 0.003f);
        ps.scale(scale, -scale, scale);
        Matrix4f tm = ps.last().pose();
        font.drawInBatch(Component.translatable("radar_fcs.monitor.range", Math.round(range)).getString(), 0, 0, TEXT, false, tm,
                buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        String right1 = Component.translatable("radar_fcs.monitor.targets", count).getString();
        float boxW = (w - 2 * e * 1.6f) / scale;
        font.drawInBatch(right1, boxW - font.width(right1), 0, TEXT, false, tm, buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                LightTexture.FULL_BRIGHT);
        float boxH = (h - 2 * e * 1.6f) / scale;
        String bottom = be.radarCount() == 0 ? Component.translatable("radar_fcs.monitor.no_radar").getString()
                : Component.translatable("radar_fcs.monitor.radars", be.radarCount()).getString();
        font.drawInBatch(bottom, 0, boxH - font.lineHeight, be.radarCount() == 0 ? 0xFFFF5050 : TEXT, false, tm, buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();

        ps.popPose();
    }

    /** Мировое смещение -> точка экрана; null - за пределами круга. */
    private static float[] toScreen(float dx, float dz, Direction f, Direction right, float range, float r, float cx, float cy) {
        float u = dx * right.getStepX() + dz * right.getStepZ();
        float v = -(dx * f.getStepX() + dz * f.getStepZ());
        float k = r / range;
        float x = u * k, y = v * k;
        if (x * x + y * y > r * r) return null;
        return new float[]{cx + x, cy + y};
    }

    private static void rect(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z, int argb) {
        vc.addVertex(m, x0, y0, z).setColor(argb);
        vc.addVertex(m, x1, y0, z).setColor(argb);
        vc.addVertex(m, x1, y1, z).setColor(argb);
        vc.addVertex(m, x0, y1, z).setColor(argb);
    }

    private static void frame(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float t, float z, int argb) {
        rect(vc, m, x0, y0, x1, y0 + t, z, argb);
        rect(vc, m, x0, y1 - t, x1, y1, z, argb);
        rect(vc, m, x0, y0 + t, x0 + t, y1 - t, z, argb);
        rect(vc, m, x1 - t, y0 + t, x1, y1 - t, z, argb);
    }

    private static void disk(VertexConsumer vc, Matrix4f m, float cx, float cy, float r, float z, int argb) {
        int n = 48;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n, b = Math.PI * 2 * (i + 1) / n;
            vc.addVertex(m, cx, cy, z).setColor(argb);
            vc.addVertex(m, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, z).setColor(argb);
            vc.addVertex(m, cx + (float) Math.cos(b) * r, cy + (float) Math.sin(b) * r, z).setColor(argb);
            vc.addVertex(m, cx, cy, z).setColor(argb);
        }
    }

    private static void ring(VertexConsumer vc, Matrix4f m, float cx, float cy, float r, float t, float z, int argb) {
        int n = 64;
        float ri = r - t / 2, ro = r + t / 2;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n, b = Math.PI * 2 * (i + 1) / n;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a), cb = (float) Math.cos(b), sb = (float) Math.sin(b);
            vc.addVertex(m, cx + ca * ri, cy + sa * ri, z).setColor(argb);
            vc.addVertex(m, cx + ca * ro, cy + sa * ro, z).setColor(argb);
            vc.addVertex(m, cx + cb * ro, cy + sb * ro, z).setColor(argb);
            vc.addVertex(m, cx + cb * ri, cy + sb * ri, z).setColor(argb);
        }
    }

    /** Сектор от from до to градусов (0 - вверх, по часовой). */
    private static void wedge(VertexConsumer vc, Matrix4f m, float cx, float cy, float r, float from, float to, float z, int argb) {
        double a = Math.toRadians(from), b = Math.toRadians(to);
        vc.addVertex(m, cx, cy, z).setColor(argb);
        vc.addVertex(m, cx + (float) Math.sin(a) * r, cy + (float) Math.cos(a) * r, z).setColor(argb);
        vc.addVertex(m, cx + (float) Math.sin(b) * r, cy + (float) Math.cos(b) * r, z).setColor(argb);
        vc.addVertex(m, cx, cy, z).setColor(argb);
    }

    @Override
    public boolean shouldRenderOffScreen(RadarMonitorBlockEntity be) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(RadarMonitorBlockEntity be) {
        BlockPos p = be.getBlockPos();
        Direction right = be.facing().getCounterClockWise();
        return new AABB(p).expandTowards(right.getStepX() * (be.width() - 1), be.height() - 1, right.getStepZ() * (be.width() - 1))
                .inflate(0.1);
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
