package dev.radarfcs.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.radarfcs.block.MonitorIcons;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/**
 * Карта монитора: проекция мировых смещений от монитора на прямоугольник карты и рисование квадами с точной
 * обрезкой по его краю (сектора зон и лучей, линии, прямоугольники). Общая для экрана в мире (y - вверх) и окна
 * монитора (y - вниз). Вверх на карте - направление от зрителя, стоящего перед монитором, вправо - вправо от него.
 */
final class MapCanvas {
    /**
     * Слой экрана в мире: без записи глубины и без пересортировки - квады ложатся строго в порядке рисования
     * (фон, сетка, зоны, лучи, значки), поэтому слои не мерцают друг сквозь друга ни на каком расстоянии.
     * От лицевой стороны блока экран отодвинут сдвигом вида к камере (как у линий).
     */
    static final RenderType SCREEN = RenderType.create("radar_fcs_screen", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
            4096, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .createCompositeState(false));

    /**
     * Карта в окне монитора: как заливки GUI, но без отсечения граней - у фигур карты обход вершин любой
     * (у RenderType.gui() квады «изнанкой» к экрану не рисуются).
     */
    static final RenderType GUI = RenderType.create("radar_fcs_gui_map", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
            4096, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    /** Шаг веера по углу, градусов. */
    private static final float STEP = 3f;

    private final MultiBufferSource buffers;
    private final RenderType type;
    private final Matrix4f m;
    private final Direction f, right;
    final float cx, cy, k, x0, y0, x1, y1;
    /** +1 - y экрана вверх (мир), -1 - вниз (окно). */
    private final float ys;
    float z;

    /**
     * Карта с центром (cx, cy), масштабом k (единиц экрана на блок) и видимым прямоугольником [x0, x1] x [y0, y1].
     * Вершины берутся из buffers.getBuffer(type) на каждую фигуру: текст и заливки окна между фигурами не ломают буфер.
     */
    MapCanvas(MultiBufferSource buffers, RenderType type, Matrix4f m, Direction facing, float cx, float cy, float k,
              float x0, float y0, float x1, float y1, float ys) {
        this.buffers = buffers;
        this.type = type;
        this.m = m;
        this.f = facing;
        this.right = facing.getCounterClockWise();
        this.cx = cx;
        this.cy = cy;
        this.k = k;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.ys = ys;
    }

    /** Мировое смещение (dx, dz) от монитора -> точка экрана (может быть за краем карты). */
    float[] project(float dx, float dz) {
        float u = dx * right.getStepX() + dz * right.getStepZ();
        float v = -(dx * f.getStepX() + dz * f.getStepZ());
        return new float[]{cx + u * k, cy + ys * v * k};
    }

    /** Куда на экране «вверх» в координатах холста: +1 (мир) или -1 (окно). */
    float up() {
        return ys;
    }

    boolean inside(float x, float y) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }

    /** Точка экрана или null, если она за краем карты. */
    float[] toScreen(float dx, float dz) {
        float[] p = project(dx, dz);
        return inside(p[0], p[1]) ? p : null;
    }

    /** Направление на экране для мирового курса a (градусы: 0 - на юг, +Z; 90 - на восток, +X). */
    float[] dir(double a) {
        double r = Math.toRadians(a);
        double wx = Math.sin(r), wz = Math.cos(r);
        float u = (float) (wx * right.getStepX() + wz * right.getStepZ());
        float v = (float) -(wx * f.getStepX() + wz * f.getStepZ());
        return new float[]{u, ys * v};
    }

    /** Направление на север (-Z) на экране: одна из осей. */
    float[] north() {
        return new float[]{-right.getStepZ(), ys * f.getStepZ()};
    }

    private VertexConsumer vc() {
        return buffers.getBuffer(type);
    }

    /** Прямоугольник без обрезки. */
    void rect(float ax, float ay, float bx, float by, int argb) {
        VertexConsumer vc = vc();
        vc.addVertex(m, ax, ay, z).setColor(argb);
        vc.addVertex(m, bx, ay, z).setColor(argb);
        vc.addVertex(m, bx, by, z).setColor(argb);
        vc.addVertex(m, ax, by, z).setColor(argb);
    }

    /** Прямоугольник, обрезанный краем карты. */
    void clippedRect(float ax, float ay, float bx, float by, int argb) {
        float l = Math.max(x0, Math.min(ax, bx)), r = Math.min(x1, Math.max(ax, bx));
        float b = Math.max(y0, Math.min(ay, by)), t = Math.min(y1, Math.max(ay, by));
        if (l < r && b < t) rect(l, b, r, t, argb);
    }

    /** Выпуклый многоугольник (xs, ys, n вершин), обрезанный краем карты. */
    void poly(float[] px, float[] py, int n, int argb) {
        float[][] c = clip(px, py, n);
        if (c == null) return;
        float[] qx = c[0], qy = c[1];
        int cn = qx.length;
        VertexConsumer vc = vc();
        for (int i = 1; i + 1 < cn; i++) {
            vc.addVertex(m, qx[0], qy[0], z).setColor(argb);
            vc.addVertex(m, qx[i], qy[i], z).setColor(argb);
            vc.addVertex(m, qx[i + 1], qy[i + 1], z).setColor(argb);
            vc.addVertex(m, qx[i + 1], qy[i + 1], z).setColor(argb);
        }
    }

    /** Сектор из точки (px, py) между мировыми курсами from..to радиусом radius (единицы экрана). */
    void sector(float px, float py, float from, float to, float radius, int argb) {
        if (to <= from || radius <= 0) return;
        float[][] d = dirs(from, to);
        float[] ax = new float[3], ay = new float[3];
        float[] d0 = d[0];
        for (int i = 1; i < d.length; i++) {
            float[] d1 = d[i];
            ax[0] = px;
            ay[0] = py;
            ax[1] = px + d0[0] * radius;
            ay[1] = py + d0[1] * radius;
            ax[2] = px + d1[0] * radius;
            ay[2] = py + d1[1] * radius;
            poly(ax, ay, 3, argb);
            d0 = d1;
        }
    }

    /**
     * Направления веера from..to: крайние - ровно from и to, у полного круга последнее - то же, что первое. Соседние
     * клинья (и соседние сектора с общей границей) получают одинаковые вершины - без щелей и двойного наложения.
     */
    private float[][] dirs(float from, float to) {
        int n = Math.max(1, (int) Math.ceil((to - from) / STEP));
        float[][] d = new float[n + 1][];
        for (int i = 0; i < n; i++) d[i] = dir(i == 0 ? from : from + (to - from) * i / n);
        d[n] = to - from >= 360 ? d[0] : dir(to);
        return d;
    }

    /** Дуга окружности радиуса radius (по средней линии) толщиной th между курсами from..to. */
    void arc(float px, float py, float from, float to, float radius, float th, int argb) {
        if (to <= from || radius <= 0) return;
        float[][] d = dirs(from, to);
        float ri = Math.max(0, radius - th / 2), ro = radius + th / 2;
        float[] ax = new float[4], ay = new float[4];
        float[] d0 = d[0];
        for (int i = 1; i < d.length; i++) {
            float[] d1 = d[i];
            ax[0] = px + d0[0] * ri;
            ay[0] = py + d0[1] * ri;
            ax[1] = px + d0[0] * ro;
            ay[1] = py + d0[1] * ro;
            ax[2] = px + d1[0] * ro;
            ay[2] = py + d1[1] * ro;
            ax[3] = px + d1[0] * ri;
            ay[3] = py + d1[1] * ri;
            poly(ax, ay, 4, argb);
            d0 = d1;
        }
    }

    /** Отрезок толщиной th, обрезанный краем карты. */
    void line(float ax, float ay, float bx, float by, float th, int argb) {
        float dx = bx - ax, dy = by - ay, len = (float) Math.hypot(dx, dy);
        if (len < 1e-6f) return;
        float nx = -dy / len * th / 2, ny = dx / len * th / 2;
        poly(new float[]{ax + nx, bx + nx, bx - nx, ax - nx}, new float[]{ay + ny, by + ny, by - ny, ay - ny}, 4, argb);
    }

    /** Значок формы shape с центром (x, y) и полуразмером size (фигура - вершиной вверх и в окне, и в мире). */
    void shape(MonitorIcons.Shape shape, float x, float y, float size, int argb) {
        for (float[] sp : SPANS[shape.ordinal()]) {
            rect(x + sp[2] * size, y + ys * sp[0] * size, x + sp[3] * size, y + ys * sp[1] * size, argb);
        }
    }

    private static final float[][][] SPANS = new float[MonitorIcons.Shape.values().length][][];

    static {
        for (MonitorIcons.Shape sh : MonitorIcons.Shape.values()) SPANS[sh.ordinal()] = MonitorIcons.spans(sh, 8);
    }

    /** Отсечение выпуклого многоугольника прямоугольником карты (Сазерленд - Ходжмен); null - ничего не осталось. */
    private float[][] clip(float[] px, float[] py, int n) {
        float[] ix = java.util.Arrays.copyOf(px, n), iy = java.util.Arrays.copyOf(py, n);
        int cn = n;
        for (int edge = 0; edge < 4 && cn > 0; edge++) {
            float[] ox = new float[cn * 2], oy = new float[cn * 2];
            int on = 0;
            for (int i = 0; i < cn; i++) {
                float ax = ix[i], ay = iy[i], bx = ix[(i + 1) % cn], by = iy[(i + 1) % cn];
                float da = dist(edge, ax, ay), db = dist(edge, bx, by);
                if (da >= 0) {
                    ox[on] = ax;
                    oy[on++] = ay;
                }
                if ((da >= 0) != (db >= 0)) {
                    // точка на краю - от «меньшего» конца отрезка: у соседних фигур с общим ребром она совпадает до бита
                    boolean swap = ax > bx || (ax == bx && ay > by);
                    float sx = swap ? bx : ax, sy = swap ? by : ay, ex = swap ? ax : bx, ey = swap ? ay : by;
                    float ds = swap ? db : da, de = swap ? da : db;
                    float t = ds / (ds - de);
                    ox[on] = sx + (ex - sx) * t;
                    oy[on++] = sy + (ey - sy) * t;
                }
            }
            ix = ox;
            iy = oy;
            cn = on;
        }
        if (cn < 3) return null;
        return new float[][]{java.util.Arrays.copyOf(ix, cn), java.util.Arrays.copyOf(iy, cn)};
    }

    /** Насколько точка внутри полуплоскости края карты (>= 0 - внутри). */
    private float dist(int edge, float x, float y) {
        return switch (edge) {
            case 0 -> x - x0;
            case 1 -> x1 - x;
            case 2 -> y - y0;
            default -> y1 - y;
        };
    }
}
