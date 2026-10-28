package dev.radarfcs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.block.RadarMonitorBlockEntity.Blip;
import dev.radarfcs.block.RadarMonitorBlockEntity.Site;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import org.joml.Matrix4f;

/**
 * Экран радарного монитора: круговая развёртка. Вверх на экране - направление от зрителя
 * (куда он смотрит, стоя перед монитором), вправо - вправо от зрителя. Цвета отметок: под огнём - красный,
 * свои - зелёный, неактивные - серый, угрозы - оранжевый, мирные и прочие - голубой; назначенная - жёлтая рамка,
 * отметка под взглядом - белая рамка и сведения о цели (имя, координаты, дальность, высота, скорость).
 */
public class RadarMonitorRenderer implements BlockEntityRenderer<RadarMonitorBlockEntity> {
    private static final int BG = 0xF0060D08;
    private static final int DISK = 0xF00B2412;
    private static final int RING = 0xB040C060;
    private static final int GRID = 0x6040C060;
    private static final int SWEEP = 0x60FF60;
    private static final int SITE = 0xFF60FF80;
    private static final int HOSTILE = 0xFFFFA020;
    private static final int NEUTRAL = 0xFF50C8FF;
    private static final int FRIENDLY = 0xFF40FF60;
    private static final int INACTIVE = 0xFF7A847A;
    private static final int HOVER = 0xFFFFFFFF;
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
        // точка экрана под взглядом игрока (в тех же осях: X вправо, Y вверх от центра ведущего блока)
        float[] look = lookPoint(be, f, right, w, h);
        Blip hovered = null;
        float hoverD = Math.max(s * 3f, 0.06f);
        int count = 0;
        List<Blip> blips = be.blips();
        // неактивные и свои - снизу, активные - поверх
        for (int pass = 0; pass < 2; pass++) {
            for (Blip b : blips) {
                boolean minor = (b.flags() & (RadarMonitorBlockEntity.FLAG_INACTIVE | RadarMonitorBlockEntity.FLAG_FRIENDLY)) != 0
                        && (b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) == 0;
                if (minor != (pass == 0)) continue;
                float[] p = toScreen(b.dx(), b.dz(), f, right, range, r, cx, cy);
                if (p == null) continue;
                if (!minor) count++;
                boolean ship = (b.flags() & RadarMonitorBlockEntity.FLAG_SHIP) != 0;
                float bs = (ship ? s * 1.5f : s) * (minor ? 0.7f : 1f);
                rect(vc, m, p[0] - bs, p[1] - bs, p[0] + bs, p[1] + bs, minor ? 0.0018f : 0.002f, blipColor(b));
                if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) {
                    float o = bs * 2.2f, th = bs * 0.45f;
                    frame(vc, m, p[0] - o, p[1] - o, p[0] + o, p[1] + o, th, 0.0024f, DESIGNATED);
                }
                if (look != null) {
                    float d = (float) Math.hypot(p[0] - look[0], p[1] - look[1]);
                    if (d < hoverD) {
                        hoverD = d;
                        hovered = b;
                    }
                }
            }
        }
        if (hovered != null) {
            float[] p = toScreen(hovered.dx(), hovered.dz(), f, right, range, r, cx, cy);
            float o = s * 1.8f, th = s * 0.3f;
            frame(vc, m, p[0] - o, p[1] - o, p[0] + o, p[1] + o, th, 0.0026f, HOVER);
        } else if (look != null) {
            // курсор взгляда
            float c = s * 0.8f, th = s * 0.15f;
            rect(vc, m, look[0] - c, look[1] - th, look[0] + c, look[1] + th, 0.0026f, 0xA0FFFFFF);
            rect(vc, m, look[0] - th, look[1] - c, look[0] + th, look[1] + c, 0.0026f, 0xA0FFFFFF);
        }

        // сведения о цели под взглядом (или назначенной): подложка - квадом здесь, текст - ниже. Свой фон текста шрифта
        // лежит почти в одной плоскости с буквами и на расстоянии перекрывает их (мерцание по глубине)
        float scale = 0.0075f * (float) Math.sqrt(Math.min(w, h));
        float boxW = (w - 2 * e * 1.6f) / scale;
        Blip info = hovered;
        if (info == null) for (Blip b : blips) if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) info = b;
        List<Component> lines = info == null ? List.of() : RadarMonitorScreen.describe(be, info);
        int widest = 1;
        for (Component c : lines) widest = Math.max(widest, font.width(c));
        float k = Math.min(1f, boxW / widest);
        if (!lines.isEmpty()) {
            float ox = -0.5f + e * 1.6f, oy = h - 0.5f - e * 1.6f;
            float y0 = font.lineHeight + 2 - k, y1 = font.lineHeight + 2 + k * (lines.size() * (font.lineHeight + 1));
            rect(vc, m, ox - k * scale, oy - y1 * scale, ox + (widest + 1) * k * scale, oy - y0 * scale, 0.0028f, 0xC0000000);
        }

        // подписи - последними: текст берёт из буферов свой тип рендера, и буфер квадов после него недействителен
        ps.pushPose();
        ps.translate(-0.5f + e * 1.6f, h - 0.5f - e * 1.6f, 0.003f);
        ps.scale(scale, -scale, scale);
        Matrix4f tm = ps.last().pose();
        font.drawInBatch(Component.translatable("radar_fcs.monitor.range", Math.round(range)).getString(), 0, 0, TEXT, false, tm,
                buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        String right1 = Component.translatable("radar_fcs.monitor.targets", count).getString();
        font.drawInBatch(right1, boxW - font.width(right1), 0, TEXT, false, tm, buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                LightTexture.FULL_BRIGHT);
        float boxH = (h - 2 * e * 1.6f) / scale;
        if (!lines.isEmpty()) {
            // сведения о цели под строкой дальности; на маленьком экране - мельче, чтобы влезли
            ps.pushPose();
            ps.translate(0, font.lineHeight + 2, 0);
            ps.scale(k, k, 1);
            Matrix4f im = ps.last().pose();
            float ty = 0;
            for (Component c : lines) {
                font.drawInBatch(c, 0, ty, 0xFFFFFFFF, false, im, buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                        LightTexture.FULL_BRIGHT);
                ty += font.lineHeight + 1;
            }
            ps.popPose();
        }
        String bottom = be.radarCount() == 0 ? Component.translatable("radar_fcs.monitor.no_radar").getString()
                : Component.translatable("radar_fcs.monitor.radars", be.radarCount()).getString();
        font.drawInBatch(bottom, 0, boxH - font.lineHeight, be.radarCount() == 0 ? 0xFFFF5050 : TEXT, false, tm, buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();

        ps.popPose();
    }

    /** Цвет отметки по её флагам (общий для экрана в мире и окна монитора). */
    public static int blipColor(Blip b) {
        int fl = b.flags();
        if ((fl & RadarMonitorBlockEntity.FLAG_ENGAGED) != 0) return ENGAGED;
        if ((fl & RadarMonitorBlockEntity.FLAG_FRIENDLY) != 0) return FRIENDLY;
        if ((fl & RadarMonitorBlockEntity.FLAG_INACTIVE) != 0) return INACTIVE;
        return (fl & RadarMonitorBlockEntity.FLAG_HOSTILE) != 0 ? HOSTILE : NEUTRAL;
    }

    /** Куда на экран этого монитора смотрит игрок (локальные X, Y) или null. */
    private static float[] lookPoint(RadarMonitorBlockEntity be, Direction f, Direction right, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != f) return null;
        BlockPos d = hit.getBlockPos().subtract(be.getBlockPos());
        int along = d.getX() * right.getStepX() + d.getZ() * right.getStepZ();
        int across = d.getX() * f.getStepX() + d.getZ() * f.getStepZ();
        if (across != 0 || along < 0 || along >= w || d.getY() < 0 || d.getY() >= h) return null;
        Vec3 rel = hit.getLocation().subtract(Vec3.atCenterOf(be.getBlockPos()));
        return new float[]{(float) (rel.x * right.getStepX() + rel.z * right.getStepZ()), (float) rel.y};
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
