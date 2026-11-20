package dev.radarfcs.core;

/**
 * Пределы поворота в каждую сторону (контроллеры наводки, приводы орудия): от нуля можно повернуть не больше чем на
 * {@code neg} градусов в отрицательную сторону и {@code pos} - в положительную. Значение {@link #FREE} (и больше) -
 * без ограничения. Угол «сколько повёрнуто от нуля» накопительный (может быть больше 180 и 360), поэтому путь к цели
 * выбирается так, чтобы не пройти через запретный сектор: при необходимости - длинной дорогой.
 */
public final class AngleLimit {
    /** Значение «без ограничения» (правый край ползунка). */
    public static final int FREE = 361;
    public static final int MIN = 1;

    private AngleLimit() {}

    public static boolean free(int limit) {
        return limit >= FREE;
    }

    public static int clean(int limit) {
        return Math.max(MIN, Math.min(FREE, limit));
    }

    /** Поворот на этот тик: {@code delta} - сколько повернуть (со знаком), {@code reachable} - цель внутри пределов. */
    public record Plan(double delta, boolean reachable) {}

    /**
     * Как повернуть из накопленного угла {@code turned} к направлению, которое отстоит на {@code delta} (кратчайший
     * поворот, -180..180). Если в пределах есть такой же угол по модулю 360 - поворот к ближайшему из них
     * (возможно, длинной дорогой); иначе - к пределу, ближайшему к цели, и {@code reachable = false}.
     */
    public static Plan plan(double turned, double delta, int neg, int pos) {
        if (free(neg) && free(pos)) return new Plan(delta, true);
        double lo = free(neg) ? Double.NEGATIVE_INFINITY : -neg, hi = free(pos) ? Double.POSITIVE_INFINITY : pos;
        double best = Double.NaN;
        for (int k = -3; k <= 3; k++) {
            double c = delta + 360.0 * k;
            double t = turned + c;
            if (t >= lo - 1e-6 && t <= hi + 1e-6 && (Double.isNaN(best) || Math.abs(c) < Math.abs(best))) best = c;
        }
        if (!Double.isNaN(best)) return new Plan(best, true);
        double want = turned + delta;
        double bound;
        if (Double.isInfinite(lo)) bound = hi;
        else if (Double.isInfinite(hi)) bound = lo;
        else bound = circular(want, lo) <= circular(want, hi) ? lo : hi;
        return new Plan(bound - turned, false);
    }

    /** То же для угла без оборотов (наклон): {@code current + delta} зажимается в [-neg, pos]. */
    public static Plan planLinear(double current, double delta, int neg, int pos) {
        double lo = free(neg) ? Double.NEGATIVE_INFINITY : -neg, hi = free(pos) ? Double.POSITIVE_INFINITY : pos;
        double want = current + delta;
        double t = Math.max(lo, Math.min(hi, want));
        return new Plan(t - current, Math.abs(t - want) < 0.5);
    }

    /** Накопленный угол по новому абсолютному (-180..180 или любому): ветка, ближайшая к прежнему накопленному. */
    public static double unwrap(double turned, double absolute) {
        return turned + Aim.wrap180(absolute - turned);
    }

    private static double circular(double a, double b) {
        return Math.abs(Aim.wrap180(a - b));
    }
}
