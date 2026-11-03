package dev.radarfcs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.radarfcs.block.MonitorIcons;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.block.RadarMonitorBlockEntity.Blip;
import dev.radarfcs.block.RadarMonitorBlockEntity.Site;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import dev.radarfcs.compat.RadarLink;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;

/**
 * Экран радарного монитора: карта со стороной 64-2048 блоков, в центре - сам монитор. Вверх на экране - направление
 * от зрителя (куда он смотрит, стоя перед монитором), вправо - вправо от зрителя. Радары сети - метки с зоной действия
 * и лучом, который вращается вместе с антенной. Цвета отметок: под огнём - красный,
 * свои - зелёный, неактивные - серый, угрозы - оранжевый, мирные и прочие - голубой; назначенная - жёлтая рамка,
 * отметка под взглядом - белая рамка и сведения о цели (имя, координаты, дальность, высота, скорость).
 */
public class RadarMonitorRenderer implements BlockEntityRenderer<RadarMonitorBlockEntity> {
    private static final int BG = 0xFF060D08;
    private static final int BEZEL = 0xFF3A3F48;
    /** Линии сетки: ось через монитор, каждая вторая, промежуточные (непрозрачные, смешаны с фоном). */
    private static final int AXIS = 0xFF2E8845;
    private static final int GRID = 0xFF1C5029;
    private static final int GRID_MINOR = 0xFF11301A;
    /** Засечки шкалы дальности. */
    private static final int SCALE_TICK = 0xFF7FE59A;
    private static final int SWEEP = 0x60FF60;
    private static final int SITE = 0xFF60FF80;
    private static final int HOSTILE = 0xFFFFA020;
    private static final int NEUTRAL = 0xFF50C8FF;
    private static final int FRIENDLY = 0xFF40FF60;
    private static final int INACTIVE = 0xFF7A847A;
    private static final int HOVER = 0xFFFFFFFF;
    private static final int ENGAGED = 0xFFFF3030;
    private static final int DESIGNATED = 0xFFFFFF40;
    /** Крест исключённой цели. */
    static final int EXCLUDED = 0xE0FF5A5A;
    private static final int TEXT = 0xFF70FF90;
    private static final int RING_TEXT = 0xFF5FC878;

    /** Подпись круга дальности: метры (от 1000 - километры с десятыми). */
    static String ringLabel(float meters) {
        if (meters >= 1000) {
            float km = Math.round(meters / 100f) / 10f;
            return (km == Math.rint(km) ? Integer.toString((int) km) : Float.toString(km)) + " км";
        }
        return Math.round(meters) + " м";
    }

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
        // экран - чуть перед лицевой стороной блока; квады карты ложатся без записи глубины строго по порядку
        // (MapCanvas.SCREEN) и не мерцают друг сквозь друга; текст - выше, со своим сдвигом
        ps.translate(0, 0, 0.5 + 0.01);
        ps.scale(1, 1, 10);
        Matrix4f m = ps.last().pose();

        float e = RadarMonitorBlockEntity.BEZEL;
        float cx = (w - 1) / 2f, cy = (h - 1) / 2f;
        float half = be.mapSide() / 2f;
        MapCanvas c = new MapCanvas(buffers, MapCanvas.SCREEN, m, f, cx, cy, (float) RadarMonitorBlockEntity.mapK(w, h, half),
                -0.5f + e, -0.5f + e, w - 0.5f - e, h - 0.5f - e, 1);
        // рамка и экран - сплошными квадами на всю стену: стыки блоков не видны
        c.rect(-0.5f, -0.5f, w - 0.5f, h - 0.5f, BEZEL);
        c.rect(c.x0, c.y0, c.x1, c.y1, BG);
        if (!be.powered()) {
            // нет электричества: тёмный экран с надписью
            float sc = 0.0075f * (float) Math.sqrt(Math.min(w, h));
            String msg = Component.translatable("radar_fcs.monitor.no_energy_short").getString();
            ps.pushPose();
            ps.translate(cx, cy, 0.003f);
            ps.scale(sc, -sc, sc);
            font.drawInBatch(msg, -font.width(msg) / 2f, -font.lineHeight / 2f, 0xFFFF5050, false, ps.last().pose(), buffers,
                    Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            ps.popPose();
            ps.popPose();
            return;
        }
        float r = Math.min(w, h) / 2f - e;                       // полстороны квадрата карты
        float th = Math.max(0.006f, r * 0.012f);
        float s = Math.max(0.012f, r * 0.045f);
        drawGrid(c, half, th);
        drawRadars(c, be, partialTick, s, th * 0.8f);
        // центр - сам монитор
        c.rect(cx - s * 0.9f, cy - s * 0.25f, cx + s * 0.9f, cy + s * 0.25f, 0xFFFFFFFF);
        c.rect(cx - s * 0.25f, cy - s * 0.9f, cx + s * 0.25f, cy + s * 0.9f, 0xFFFFFFFF);
        // точка экрана под взглядом игрока (в тех же осях: X вправо, Y вверх от центра ведущего блока)
        float[] look = lookPoint(be, f, right, w, h);
        Blip hovered = null;
        RadarMonitorBlockEntity.Mark hoveredMark = null;
        int hoveredPin = -1;
        float hoverD = Math.max(s * 3f, 0.06f);
        int count = 0;
        List<Blip> blips = be.blips();
        boolean always = be.labels() == RadarMonitorBlockEntity.LABELS_ALWAYS;
        List<LabelLayout.Label> labels = new ArrayList<>();
        float ls = 0.0055f * (float) Math.sqrt(Math.min(w, h));
        // метки игрока (только для глаз): кольцо с точкой и подпись всегда
        if ((be.show() & RadarMonitorBlockEntity.Show.PINS.bit) != 0) {
            List<RadarMonitorBlockEntity.Pin> pins = be.pins();
            for (int i = 0; i < pins.size(); i++) {
                RadarMonitorBlockEntity.Pin pin = pins.get(i);
                float[] p = c.toScreen(pinDx(be, pin), pinDz(be, pin));
                if (p == null) continue;
                int col = MonitorIcons.PALETTE[pin.color()];
                drawPin(c, p[0], p[1], s, col);
                if (look != null) {
                    float d = (float) Math.hypot(p[0] - look[0], p[1] - look[1]);
                    if (d < hoverD) {
                        hoverD = d;
                        hoveredPin = i;
                    }
                }
                labels.add(label(c, p[0], s * 1.4f, p[1], pin.label(i), col, LabelLayout.RANK_ACTIVE, ls));
            }
        }
        // метки наземных транспондеров
        int tIcon = be.icon(MonitorIcons.TRANSPONDER);
        for (RadarMonitorBlockEntity.Mark mk : be.marks()) {
            float[] p = c.toScreen(mk.dx(), mk.dz());
            if (p == null) continue;
            float ms = s * MonitorIcons.SIZES[MonitorIcons.size(tIcon)];
            int col = MonitorIcons.color(tIcon) == 0 ? (mk.friendly() ? FRIENDLY : HOSTILE) : MonitorIcons.PALETTE[MonitorIcons.color(tIcon)];
            c.shape(MonitorIcons.shape(tIcon), p[0], p[1], ms, col);
            if (look != null) {
                float d = (float) Math.hypot(p[0] - look[0], p[1] - look[1]);
                if (d < hoverD) {
                    hoverD = d;
                    hoveredMark = mk;
                    hoveredPin = -1;
                }
            }
            if (always && !mk.name().isEmpty()) labels.add(label(c, p[0], ms * 1.4f, p[1], mk.name(), col, LabelLayout.RANK_MINOR, ls));
        }
        // неактивные и свои - снизу, активные - поверх
        for (int pass = 0; pass < 2; pass++) {
            for (Blip b : blips) {
                boolean minor = (b.flags() & (RadarMonitorBlockEntity.FLAG_INACTIVE | RadarMonitorBlockEntity.FLAG_FRIENDLY
                        | RadarMonitorBlockEntity.FLAG_EXCLUDED)) != 0 && (b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) == 0;
                if (minor != (pass == 0)) continue;
                float[] p = c.toScreen(b.dx(), b.dz());
                if (p == null) continue;
                if (!minor) count++;
                int ic = be.icon(b.category());
                float bs = s * MonitorIcons.SIZES[MonitorIcons.size(ic)] * (minor ? 0.8f : 1f);
                int col = iconColor(b, ic);
                c.shape(MonitorIcons.shape(ic), p[0], p[1], bs, col);
                if ((b.flags() & RadarMonitorBlockEntity.FLAG_EXCLUDED) != 0) crossOut(c, p[0], p[1], bs);
                if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) {
                    float o = bs * 2.2f, t = bs * 0.45f;
                    frame(c, p[0] - o, p[1] - o, p[0] + o, p[1] + o, t, DESIGNATED);
                }
                if (look != null) {
                    float d = (float) Math.hypot(p[0] - look[0], p[1] - look[1]);
                    if (d < hoverD) {
                        hoverD = d;
                        hovered = b;
                        hoveredMark = null;
                        hoveredPin = -1;
                    }
                }
                if (always && !b.tag().isEmpty()) {
                    int rank = minor ? LabelLayout.RANK_MINOR
                            : (b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0 ? LabelLayout.RANK_DESIGNATED : LabelLayout.RANK_ACTIVE;
                    labels.add(label(c, p[0], bs * 1.4f, p[1], b.tag(), col, rank, ls));
                }
            }
        }
        if (look != null) {
            hoverMonitor = be.getBlockPos();
            hoverId = hovered != null ? hovered.id() : null;
        } else if (be.getBlockPos().equals(hoverMonitor)) {
            hoverId = null;
        }
        RadarMonitorBlockEntity.Pin hPin = hoveredPin >= 0 ? be.pins().get(hoveredPin) : null;
        float[] hp = hovered != null ? c.toScreen(hovered.dx(), hovered.dz())
                : hoveredMark != null ? c.toScreen(hoveredMark.dx(), hoveredMark.dz())
                : hPin != null ? c.toScreen(pinDx(be, hPin), pinDz(be, hPin)) : null;
        if (hp != null) {
            float o = s * 1.8f, t = s * 0.3f;
            frame(c, hp[0] - o, hp[1] - o, hp[0] + o, hp[1] + o, t, HOVER);
            String name = hovered != null ? hovered.tag() : hoveredMark != null ? hoveredMark.name() : "";   // подпись метки и так видна
            if (!always && !name.isEmpty()) labels.add(label(c, hp[0], o * 1.2f, hp[1], name, HOVER, LabelLayout.RANK_HOVER, ls));
        } else if (look != null) {
            // курсор взгляда
            float cs = s * 0.8f, t = s * 0.15f;
            c.rect(look[0] - cs, look[1] - t, look[0] + cs, look[1] + t, 0xA0FFFFFF);
            c.rect(look[0] - t, look[1] - cs, look[0] + t, look[1] + cs, 0xA0FFFFFF);
        }
        // подложки подписей без наложения (текст - ниже, после всех квадов)
        List<LabelLayout.Placed> placed = LabelLayout.place(labels);
        float lh = font.lineHeight * ls;
        for (LabelLayout.Placed pl : placed) {
            LabelLayout.Label lb = pl.label();
            c.rect(lb.x(), pl.y() - lh / 2 - ls, lb.x() + lb.w(), pl.y() + lh / 2, 0xB0000000);
        }
        // север: буква у края карты на оси, которая на него смотрит
        String north = Component.translatable("radar_fcs.monitor.north").getString();
        float nw = font.width(north) * ls, pad = 3 * ls;
        float[] nd = c.north();
        float nx = nd[0] > 0 ? c.x1 - pad - nw : nd[0] < 0 ? c.x0 + pad : cx - nw / 2;
        float ny = nd[1] > 0 ? c.y1 - pad - lh : nd[1] < 0 ? c.y0 + pad : cy - lh / 2;
        c.rect(nx - 1.5f * ls, ny - 1.5f * ls, nx + nw + 1.5f * ls, ny + lh + 0.5f * ls, 0xC0000000);
        // сведения о цели под взглядом (или назначенной): подложка - квадом здесь, текст - ниже. Свой фон текста шрифта
        // лежит почти в одной плоскости с буквами и на расстоянии перекрывает их (мерцание по глубине)
        float scale = 0.0075f * (float) Math.sqrt(Math.min(w, h));
        float boxW = (w - 2 * e * 1.6f) / scale, boxH = (h - 2 * e * 1.6f) / scale;
        float ox = -0.5f + e * 1.6f, oy = h - 0.5f - e * 1.6f;
        Blip info = hovered;
        if (info == null && hoveredMark == null && hPin == null) {
            for (Blip b : blips) if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) info = b;
        }
        List<Component> lines = info != null ? RadarMonitorScreen.describe(be, info)
                : hoveredMark != null ? RadarMonitorScreen.describeMark(be, hoveredMark)
                : hPin != null ? RadarMonitorScreen.describePin(be, hPin, hoveredPin) : List.of();
        int widest = 1;
        for (Component cl : lines) widest = Math.max(widest, font.width(cl));
        float k = Math.min(1f, boxW / widest);
        String mapText = Component.translatable("radar_fcs.monitor.range", be.mapSide()).getString();
        String right1 = Component.translatable("radar_fcs.monitor.targets", count).getString();
        String bottom = be.radarCount() == 0 ? Component.translatable("radar_fcs.monitor.no_radar").getString()
                : Component.translatable("radar_fcs.monitor.radars", be.radarCount()).getString();
        // подложки строк состояния: карта видна и под ними
        textBg(c, ox, oy, scale, 0, 0, font.width(mapText));
        textBg(c, ox, oy, scale, boxW - font.width(right1), 0, font.width(right1));
        textBg(c, ox, oy, scale, 0, boxH - font.lineHeight, font.width(bottom));
        List<float[]> blocked = new ArrayList<>();
        blocked.add(textRect(ox, oy, scale, 0, 0, font.width(mapText)));
        blocked.add(textRect(ox, oy, scale, boxW - font.width(right1), 0, font.width(right1)));
        blocked.add(textRect(ox, oy, scale, 0, boxH - font.lineHeight, font.width(bottom)));
        if (!lines.isEmpty()) {
            float y0 = font.lineHeight + 2 - k, y1 = font.lineHeight + 2 + k * (lines.size() * (font.lineHeight + 1));
            c.rect(ox - k * scale, oy - y1 * scale, ox + (widest + 1) * k * scale, oy - y0 * scale, 0xC0000000);
            blocked.add(new float[]{ox - k * scale, oy - y1 * scale, ox + (widest + 1) * k * scale, oy - y0 * scale});
        }
        // шкала дальности на обеих осях: не налезает на север, строки состояния, сведения и подписи целей
        blocked.add(new float[]{nx - 1.5f * ls, ny - 1.5f * ls, nx + nw + 1.5f * ls, ny + lh + 0.5f * ls});
        for (LabelLayout.Placed pl : placed) {
            LabelLayout.Label lb = pl.label();
            blocked.add(new float[]{lb.x(), pl.y() - lh / 2 - ls, lb.x() + lb.w(), pl.y() + lh / 2});
        }
        List<ScaleLabel> scaleLabels = scale(c, half, th, txt -> font.width(txt) * ls, lh, th * 3 + ls, ls, blocked);
        for (ScaleLabel sl : scaleLabels) {
            c.rect(sl.x() - sl.w() / 2 - ls, sl.y() - lh / 2 - ls, sl.x() + sl.w() / 2 + ls, sl.y() + lh / 2 + ls * 0.5f, 0xA0000000);
        }

        // подписи - последними: текст берёт из буферов свой тип рендера, и буфер квадов после него недействителен
        drawText(ps, buffers, north, nx, ny, ls, TEXT);
        for (ScaleLabel sl : scaleLabels) drawText(ps, buffers, sl.text(), sl.x() - sl.w() / 2, sl.y() - lh / 2, ls, RING_TEXT);
        for (LabelLayout.Placed pl : placed) {
            LabelLayout.Label lb = pl.label();
            drawText(ps, buffers, lb.text(), lb.x() + ls, pl.y() - lh / 2, ls, 0xFF000000 | lb.color());
        }
        ps.pushPose();
        ps.translate(ox, oy, 0.003f);
        ps.scale(scale, -scale, scale);
        Matrix4f tm = ps.last().pose();
        font.drawInBatch(mapText, 0, 0, TEXT, false, tm, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        font.drawInBatch(right1, boxW - font.width(right1), 0, TEXT, false, tm, buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                LightTexture.FULL_BRIGHT);
        if (!lines.isEmpty()) {
            // сведения о цели под строкой масштаба; на маленьком экране - мельче, чтобы влезли
            ps.pushPose();
            ps.translate(0, font.lineHeight + 2, 0);
            ps.scale(k, k, 1);
            Matrix4f im = ps.last().pose();
            float ty = 0;
            for (Component cl : lines) {
                font.drawInBatch(cl, 0, ty, 0xFFFFFFFF, false, im, buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                        LightTexture.FULL_BRIGHT);
                ty += font.lineHeight + 1;
            }
            ps.popPose();
        }
        font.drawInBatch(bottom, 0, boxH - font.lineHeight, be.radarCount() == 0 ? 0xFFFF5050 : TEXT, false, tm, buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();

        ps.popPose();
    }

    /** Строка текста в локальных координатах экрана: (x, y) - левый нижний угол строки, высота буквы - ls за пиксель. */
    private void drawText(PoseStack ps, MultiBufferSource buffers, String text, float x, float y, float ls, int color) {
        ps.pushPose();
        ps.translate(x, y + font.lineHeight * ls, 0.003f);
        ps.scale(ls, -ls, ls);
        font.drawInBatch(text, 0, 0, color, false, ps.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

    /** Подложка строки состояния: (tx, ty) и ширина tw - в пикселях шрифта от угла (ox, oy), ось y - вниз. */
    private void textBg(MapCanvas c, float ox, float oy, float scale, float tx, float ty, float tw) {
        float[] r = textRect(ox, oy, scale, tx, ty, tw);
        c.rect(r[0], r[1], r[2], r[3], 0x90000000);
    }

    /** Прямоугольник строки состояния в координатах экрана: {x0, y0, x1, y1}. */
    private float[] textRect(float ox, float oy, float scale, float tx, float ty, float tw) {
        return new float[]{ox + (tx - 1) * scale, oy - (ty + font.lineHeight) * scale, ox + (tx + tw + 1) * scale, oy - (ty - 1) * scale};
    }

    /** Деления шкалы дальности: доли половины стороны карты (от монитора до края квадрата карты). */
    static final float[] SCALE_F = {1f / 3, 0.5f, 2f / 3, 1f};

    /**
     * Сетка карты - по делениям шкалы: линии на 1/3, 1/2, 2/3 и 1 половины стороны от монитора (дальше, на широкой
     * стене, - тот же рисунок), ось через монитор - ярче всех. Цвета непрозрачные (заранее смешаны с фоном): на
     * пересечениях линий нет светлых точек.
     */
    static void drawGrid(MapCanvas c, float half, float th) {
        float unit = half * c.k;
        for (int axis = 0; axis < 2; axis++) {
            float lo = axis == 0 ? c.x0 : c.y0, hi = axis == 0 ? c.x1 : c.y1, o = axis == 0 ? c.cx : c.cy;
            for (int sign = -1; sign <= 1; sign += 2) {
                for (int n = 0; o + sign * (n + SCALE_F[0]) * unit >= lo - th && o + sign * (n + SCALE_F[0]) * unit <= hi + th; n++) {
                    for (float f : SCALE_F) {
                        float p = o + sign * (n + f) * unit;
                        int col = f == 0.5f || f == 1f ? GRID : GRID_MINOR;
                        float t = th * 0.6f;
                        if (axis == 0) c.clippedRect(p - t / 2, c.y0, p + t / 2, c.y1, col);
                        else c.clippedRect(c.x0, p - t / 2, c.x1, p + t / 2, col);
                    }
                }
            }
            if (axis == 0) c.clippedRect(o - th / 2, c.y0, o + th / 2, c.y1, AXIS);
            else c.clippedRect(c.x0, o - th / 2, c.x1, o + th / 2, AXIS);
        }
    }

    /** Подпись деления шкалы: текст, центр (x, y) и ширина - в координатах холста. */
    record ScaleLabel(String text, float x, float y, float w) {}

    /**
     * Шкала дальности на обеих осях через монитор, как на координатной плоскости: засечки на 1/3, 1/2, 2/3 и у края
     * квадрата карты; вправо и вверх - положительные расстояния, влево и вниз - со знаком минус. Подписи на
     * горизонтальной оси - поочерёдно под и над ней, на вертикальной - поочерёдно слева и справа; подпись, которая
     * налезает на уже поставленную или на занятое место (blocked: север, строки состояния, подписи целей), не ставится.
     * Сначала - край и половина, потом трети.
     */
    static List<ScaleLabel> scale(MapCanvas c, float half, float th, java.util.function.ToDoubleFunction<String> width,
                                  float lh, float gap, float pad, List<float[]> blocked) {
        float unit = half * c.k, tick = th * 3, up = c.up();
        List<float[]> taken = new ArrayList<>(blocked);
        List<ScaleLabel> out = new ArrayList<>();
        int[] order = {3, 1, 0, 2};
        for (int axis = 0; axis < 2; axis++) {
            for (int dir = 1; dir >= -1; dir -= 2) {
                for (int oi : order) {
                    float f = SCALE_F[oi];
                    float tx = axis == 0 ? c.cx + dir * f * unit : c.cx;
                    float ty = axis == 0 ? c.cy : c.cy + dir * up * f * unit;
                    if (tx < c.x0 - th || tx > c.x1 + th || ty < c.y0 - th || ty > c.y1 + th) continue;
                    if (axis == 0) c.clippedRect(tx - th * 0.6f, ty - tick, tx + th * 0.6f, ty + tick, SCALE_TICK);
                    else c.clippedRect(tx - tick, ty - th * 0.6f, tx + tick, ty + th * 0.6f, SCALE_TICK);
                    String txt = (dir < 0 ? "-" : "") + ringLabel(half * f);
                    float w = (float) width.applyAsDouble(txt);
                    int side = oi % 2 == 0 ? -1 : 1;
                    boolean edge = oi == SCALE_F.length - 1;
                    float lx, ly;
                    if (axis == 0) {
                        lx = tx - (edge ? dir * w / 2 : 0);              // у края - внутрь от засечки
                        ly = ty + side * (gap + lh / 2);
                    } else {
                        ly = ty - (edge ? dir * up * lh / 2 : 0);
                        lx = tx + side * (gap + w / 2);
                    }
                    lx = Math.max(c.x0 + pad + w / 2, Math.min(c.x1 - pad - w / 2, lx));
                    ly = Math.max(c.y0 + pad + lh / 2, Math.min(c.y1 - pad - lh / 2, ly));
                    float[] r = {lx - w / 2 - pad, ly - lh / 2 - pad, lx + w / 2 + pad, ly + lh / 2 + pad};
                    boolean free = true;
                    for (float[] o : taken) {
                        if (r[0] < o[2] && o[0] < r[2] && r[1] < o[3] && o[1] < r[3]) {
                            free = false;
                            break;
                        }
                    }
                    if (!free) continue;
                    taken.add(r);
                    out.add(new ScaleLabel(txt, lx, ly, w));
                }
            }
        }
        return out;
    }

    /**
     * Радары сети на карте: зона действия (круг у вращающегося, сектор обзора у неподвижного), луч с затухающим
     * следом - по живому курсу антенны с поправкой внутри тика, и метка самого радара.
     */
    static void drawRadars(MapCanvas c, RadarMonitorBlockEntity be, float partialTick, float iconSize, float th) {
        int show = be.show();
        boolean radars = (show & RadarMonitorBlockEntity.Show.RADARS.bit) != 0, zones = (show & RadarMonitorBlockEntity.Show.RADAR_ZONES.bit) != 0;
        if (!radars && !zones) return;
        Level level = be.getLevel();
        int zi = be.icon(MonitorIcons.RADAR_ZONE);
        int zc = (MonitorIcons.color(zi) == 0 ? SWEEP : MonitorIcons.PALETTE[MonitorIcons.color(zi)]) & 0xFFFFFF;
        int outline = mix(BG, 0xFF000000 | zc, 0.6f);
        float far = 2 * (c.x1 - c.x0 + c.y1 - c.y0);
        for (Site s : be.sites()) {
            float[] p = c.project(s.dx(), s.dz());
            float heading = level == null ? Float.NaN : liveHeading(level, s, partialTick);
            boolean known = Float.isFinite(heading);
            float half = Math.max(1f, Math.min(180f, s.fov() / 2));
            boolean rotating = s.speed() != 0 || half >= 180;
            float len = s.range() > 0 ? s.range() * c.k : far;
            if (zones && s.range() > 0 && (rotating || known)) {
                float from = rotating ? 0 : heading - half, to = rotating ? 360 : heading + half;
                if (MonitorIcons.zoneFilled(zi)) c.sector(p[0], p[1], from, to, len, MonitorIcons.zoneAlpha(zi) << 24 | zc);
                c.arc(p[0], p[1], from, to, len, th, outline);
                if (!rotating) {
                    float[] a = c.dir(from), b = c.dir(to);
                    c.line(p[0], p[1], p[0] + a[0] * len, p[1] + a[1] * len, th, outline);
                    c.line(p[0], p[1], p[0] + b[0] * len, p[1] + b[1] * len, th, outline);
                }
            }
            // луч: у неподвижного радара с показанной зоной его сектор и так виден
            if (radars && known && (rotating || !zones)) {
                c.sector(p[0], p[1], heading - half, heading + half, len, 0x48 << 24 | zc);
                if (s.speed() != 0) {
                    // след за лучом: на высоких оборотах длиннее (256 об/мин - почти 77° за тик)
                    float dir = Math.signum(s.speed()), trail = Math.max(30f, Math.min(180f, Math.abs(s.speed()) * 1.5f));
                    int seg = Math.max(6, (int) (trail / 6));
                    for (int i = 0; i < seg; i++) {
                        float a1 = heading - dir * (half + trail * i / seg), a2 = heading - dir * (half + trail * (i + 1) / seg);
                        int alpha = (int) (0x3C * (1 - (i + 0.5f) / seg));
                        c.sector(p[0], p[1], Math.min(a1, a2), Math.max(a1, a2), len, alpha << 24 | zc);
                    }
                }
            }
        }
        if (!radars) return;
        int ri = be.icon(MonitorIcons.RADAR);
        int col = MonitorIcons.color(ri) == 0 ? SITE : MonitorIcons.PALETTE[MonitorIcons.color(ri)];
        float size = iconSize * MonitorIcons.SIZES[MonitorIcons.size(ri)];
        for (Site s : be.sites()) {
            float[] p = c.toScreen(s.dx(), s.dz());
            if (p != null) c.shape(MonitorIcons.shape(ri), p[0], p[1], size, col);
        }
    }

    /**
     * Курс луча сейчас (градусы, 0 - на юг): у загруженного на клиенте радара - его собственный угол плюс поворот за
     * долю тика (так же доворачивается и антенна в мире), иначе - по данным монитора.
     */
    public static float liveHeading(Level level, Site s, float partialTick) {
        BlockEntity rb = level.getBlockEntity(BlockPos.of(s.pos()));
        if (rb != null && RadarLink.isRadar(rb)) {
            float a = RadarLink.angle(rb);
            if (Float.isFinite(a)) {
                float h = (a + RadarLink.sweepSpeed(rb) * partialTick) % 360f;
                lastHeading = h;
                return h;
            }
        }
        return Float.isFinite(s.angle()) ? s.angleAt(level.getGameTime() + partialTick) : Float.NaN;
    }

    /** Последний курс луча с живого радара (проверка плавности в тестах разработки). */
    public static volatile float lastHeading = Float.NaN;

    /** Смесь цветов a и b (t - доля b), непрозрачная. */
    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /**
     * Подпись у значка (локальные координаты экрана): справа от него на off, а если не влезает в карту - слева;
     * y - середина строки.
     */
    private LabelLayout.Label label(MapCanvas c, float ix, float off, float y, String text, int color, int rank, float ls) {
        float lw = font.width(text) * ls + 2 * ls;
        float x = ix + off - ls;
        if (x + lw > c.x1) x = ix - off - lw + ls;
        return new LabelLayout.Label(x, y, lw, font.lineHeight * ls + ls, text, color, rank);
    }

    /** Монитор (ведущий блок) и отметка под взглядом на нём в последнем кадре - для нажатия колеса мыши. */
    public static net.minecraft.core.BlockPos hoverMonitor;
    @org.jetbrains.annotations.Nullable public static String hoverId;

    /** Смещение метки игрока от центра экрана (центр блока). */
    static float pinDx(RadarMonitorBlockEntity be, RadarMonitorBlockEntity.Pin pin) {
        return (float) (pin.x() + 0.5 - be.center().x);
    }

    static float pinDz(RadarMonitorBlockEntity be, RadarMonitorBlockEntity.Pin pin) {
        return (float) (pin.z() + 0.5 - be.center().z);
    }

    /** Метка игрока: кольцо с точкой. */
    static void drawPin(MapCanvas c, float x, float y, float size, int col) {
        c.shape(MonitorIcons.Shape.RING, x, y, size * 1.1f, col);
        c.shape(MonitorIcons.Shape.CIRCLE, x, y, size * 0.35f, col);
    }

    /** Исключённая цель: значок перечёркнут крестом. */
    static void crossOut(MapCanvas c, float x, float y, float size) {
        float o = size * 1.5f, th = Math.max(size * 0.3f, 0.004f);
        c.line(x - o, y - o, x + o, y + o, th, EXCLUDED);
        c.line(x - o, y + o, x + o, y - o, th, EXCLUDED);
    }

    /** Цвет значка отметки: по состоянию или постоянный из настроек типа. */
    public static int iconColor(Blip b, int icon) {
        if ((b.flags() & RadarMonitorBlockEntity.FLAG_ENGAGED) != 0) return ENGAGED;   // под огнём - всегда красный
        if ((b.flags() & RadarMonitorBlockEntity.FLAG_EXCLUDED) != 0) return INACTIVE;  // исключённая - серая
        int c = MonitorIcons.color(icon);
        return c == 0 ? blipColor(b) : MonitorIcons.PALETTE[c];
    }

    /** Цвет отметки по её флагам (общий для экрана в мире и окна монитора). */
    public static int blipColor(Blip b) {
        int fl = b.flags();
        if ((fl & RadarMonitorBlockEntity.FLAG_ENGAGED) != 0) return ENGAGED;
        if ((fl & RadarMonitorBlockEntity.FLAG_FRIENDLY) != 0) return FRIENDLY;
        if ((fl & RadarMonitorBlockEntity.FLAG_EXCLUDED) != 0) return INACTIVE;
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

    private static void frame(MapCanvas c, float x0, float y0, float x1, float y1, float t, int argb) {
        c.rect(x0, y0, x1, y0 + t, argb);
        c.rect(x0, y1 - t, x1, y1, argb);
        c.rect(x0, y0 + t, x0 + t, y1 - t, argb);
        c.rect(x1 - t, y0 + t, x1, y1 - t, argb);
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
