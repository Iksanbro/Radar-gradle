package dev.radarfcs.core;

/**
 * Шаг числовых настроек (колесо мыши и кнопки «−/+»): дробные - от 0.1 до 1 шагом 0.1, дальше шагом 1;
 * с Shift шаг всегда в 10 раз больше. Целые - шагом 1, с Shift - 10.
 */
public final class WheelStep {
    private static final double EPS = 1e-9;

    private WheelStep() {}

    /**
     * @param dir       +1 - больше, -1 - меньше
     * @param allowZero 0 допустим (ниже min): «нет», «без ограничения»
     */
    public static double fractional(double v, int dir, boolean shift, double min, double max, boolean allowZero) {
        if (dir == 0) return v;
        if (dir > 0 && v < min - EPS) return round(Math.min(max, shift ? Math.max(min, 1) : min));
        double step = (dir > 0 ? v < 1 - EPS : v <= 1 + EPS) ? 0.1 : 1;
        if (shift) step *= 10;
        double n = round(v + Math.signum(dir) * step);
        if (n < min - EPS) n = v > min + EPS ? min : allowZero ? 0 : min;
        return round(Math.min(max, n));
    }

    public static int integer(int v, int dir, boolean shift, int min, int max) {
        long n = (long) v + (long) Integer.signum(dir) * (shift ? 10 : 1);
        return (int) Math.max(min, Math.min(max, n));
    }

    /** До 0.1. */
    public static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
