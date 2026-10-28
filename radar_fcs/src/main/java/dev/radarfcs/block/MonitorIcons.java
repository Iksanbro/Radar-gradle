package dev.radarfcs.block;

import dev.radarfcs.core.TargetCategory;
import java.util.Arrays;

/**
 * Значки радарного монитора по типам: форма, цвет («по состоянию» или постоянный), размер. Хранятся у ведущего блока
 * экрана упакованными в int: форма - биты 0-3, цвет - 4-7, размер - 8-9. Индексы типов: категории целей
 * (TargetCategory) и TRANSPONDER - метки наземных транспондеров.
 */
public final class MonitorIcons {
    public enum Shape { SQUARE, CIRCLE, TRIANGLE, DIAMOND, CROSS, RING }

    /** Цвет 0 - по состоянию (угроза, мирная, своя, неактивная, под огнём), дальше - постоянные. */
    public static final int[] PALETTE = {0, 0xFFFFFFFF, 0xFFFF4040, 0xFFFFA020, 0xFFFFE040, 0xFF40FF60, 0xFF40E0FF, 0xFF4080FF, 0xFFFF60FF};
    public static final String[] COLOR_KEYS = {"auto", "white", "red", "orange", "yellow", "green", "cyan", "blue", "magenta"};
    public static final float[] SIZES = {0.7f, 1.0f, 1.5f};

    public static final int TRANSPONDER = TargetCategory.values().length;
    public static final int COUNT = TRANSPONDER + 1;

    private MonitorIcons() {}

    public static int pack(Shape shape, int color, int size) {
        return (shape.ordinal() & 15) | (Math.floorMod(color, PALETTE.length) & 15) << 4 | (Math.floorMod(size, SIZES.length) & 3) << 8;
    }

    public static Shape shape(int packed) {
        Shape[] s = Shape.values();
        return s[Math.floorMod(packed & 15, s.length)];
    }

    public static int color(int packed) {
        return Math.floorMod((packed >> 4) & 15, PALETTE.length);
    }

    public static int size(int packed) {
        return Math.floorMod((packed >> 8) & 3, SIZES.length);
    }

    /** Значки по умолчанию: игроки - треугольник, враждебные - квадрат, мирные - круг, корабли - крупный ромб, ... */
    public static int[] defaults() {
        int[] d = new int[COUNT];
        d[TargetCategory.PLAYERS.ordinal()] = pack(Shape.TRIANGLE, 0, 1);
        d[TargetCategory.HOSTILE.ordinal()] = pack(Shape.SQUARE, 0, 1);
        d[TargetCategory.PASSIVE.ordinal()] = pack(Shape.CIRCLE, 0, 1);
        d[TargetCategory.SHIPS.ordinal()] = pack(Shape.DIAMOND, 0, 2);
        d[TargetCategory.CONTRAPTIONS.ordinal()] = pack(Shape.SQUARE, 0, 2);
        d[TargetCategory.PROJECTILES.ordinal()] = pack(Shape.CROSS, 0, 0);
        d[TargetCategory.OTHER.ordinal()] = pack(Shape.CIRCLE, 0, 0);
        d[TRANSPONDER] = pack(Shape.RING, 6, 1);
        return d;
    }

    public static int[] sanitize(int[] in) {
        int[] d = defaults();
        if (in == null) return d;
        for (int i = 0; i < Math.min(in.length, COUNT); i++) d[i] = pack(shape(in[i]), color(in[i]), size(in[i]));
        return d;
    }

    /**
     * Горизонтальные полосы фигуры в единичном квадрате [-1, 1]: {y0, y1, x0, x1} для rows строк.
     * Общая разметка для экрана в мире (квады) и окна монитора (заливки).
     */
    public static float[][] spans(Shape shape, int rows) {
        float[][] out = new float[rows * 2][];
        int n = 0;
        for (int r = 0; r < rows; r++) {
            float y0 = -1 + 2f * r / rows, y1 = -1 + 2f * (r + 1) / rows, ym = (y0 + y1) / 2;
            switch (shape) {
                case SQUARE -> out[n++] = new float[]{y0, y1, -1, 1};
                case CIRCLE -> {
                    float w = (float) Math.sqrt(Math.max(0, 1 - ym * ym));
                    out[n++] = new float[]{y0, y1, -w, w};
                }
                case TRIANGLE -> {                                      // вершиной вверх (y вверх)
                    float w = (1 - (ym + 1) / 2) ;
                    out[n++] = new float[]{y0, y1, -w, w};
                }
                case DIAMOND -> {
                    float w = 1 - Math.abs(ym);
                    out[n++] = new float[]{y0, y1, -w, w};
                }
                case CROSS -> {
                    if (Math.abs(ym) < 0.3f) out[n++] = new float[]{y0, y1, -1, 1};
                    else out[n++] = new float[]{y0, y1, -0.3f, 0.3f};
                }
                case RING -> {
                    float wo = (float) Math.sqrt(Math.max(0, 1 - ym * ym));
                    float wi = Math.abs(ym) < 0.55f ? (float) Math.sqrt(Math.max(0, 0.3025f - ym * ym)) : 0;
                    if (wi <= 0) out[n++] = new float[]{y0, y1, -wo, wo};
                    else {
                        out[n++] = new float[]{y0, y1, -wo, -wi};
                        out[n++] = new float[]{y0, y1, wi, wo};
                    }
                }
            }
        }
        return Arrays.copyOf(out, n);
    }
}
