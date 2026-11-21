package dev.radarfcs.core;

/** Угловая арифметика и привод наведения. */
public final class Aim {
    private Aim() {}

    public static double wrap180(double a) {
        double r = ((a + 180) % 360 + 360) % 360 - 180;
        return r == -180 ? 180 : r;
    }

    public static double norm360(double a) {
        return ((a % 360) + 360) % 360;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Шаг привода к цели: не быстрее slew за тик; ошибка меньше deadband не отрабатывается
     * (нет дрожания и бесконечного доводения).
     */
    public static double stepAngle(double current, double target, double slew, double deadband) {
        double e = wrap180(target - current);
        if (Math.abs(e) <= deadband) return current;
        return current + clamp(e, -slew, slew);
    }

    public static double stepLinear(double current, double target, double slew, double deadband) {
        double e = target - current;
        if (Math.abs(e) <= deadband) return current;
        return current + clamp(e, -slew, slew);
    }
}
