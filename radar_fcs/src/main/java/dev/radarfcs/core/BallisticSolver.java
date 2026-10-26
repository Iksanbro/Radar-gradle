package dev.radarfcs.core;

import java.util.function.Consumer;

/**
 * Решатель стрельбы.
 * Численно интегрирует полёт по модели {@link FlightModel} (тем же шагом, что и CBC),
 * подбирает угол возвышения бисекцией (настильная или навесная траектория)
 * и итеративно уточняет упреждение: время полёта -> позиция цели -> новое решение.
 * Точка вылета зависит от направления ствола (дульный срез смещён от оси вращения).
 */
public final class BallisticSolver {

    /** Решение: углы в конвенции Minecraft (yaw 0 = юг, pitch > 0 вверх). */
    public record Solution(double yawDeg, double pitchDeg, double flightTicks, Vec aimPoint, Vec muzzle,
                           double horizontal, Vec direction) {}

    /** Мировая точка вылета снаряда для заданного направления ствола. */
    @FunctionalInterface
    public interface Muzzle {
        Vec at(Vec direction);

        static Muzzle fixed(Vec p) { return d -> p; }

        static Muzzle offset(Vec pivot, double distance) { return d -> pivot.addScaled(d, distance); }
    }

    private static final double HINT_WINDOW = Math.toRadians(2.5);
    private static final double SCAN_STEP = Math.toRadians(2.0);
    private static final int BISECT = 18;

    private final double minPitch;
    private final double maxPitch;
    private final boolean highArc;

    public BallisticSolver(double minPitchDeg, double maxPitchDeg, boolean highArc) {
        this.minPitch = Math.toRadians(Math.min(minPitchDeg, maxPitchDeg));
        this.maxPitch = Math.toRadians(Math.max(minPitchDeg, maxPitchDeg));
        this.highArc = highArc;
    }

    /** @return {высота на дальности horiz, время полёта} или null, если снаряд не долетает */
    public static double[] simulate(double pitch, FlightModel m, double horiz) {
        double s = m.muzzleSpeed();
        double vx = s * Math.cos(pitch), vy = s * Math.sin(pitch);
        double x = 0, y = 0;
        int max = Math.max(1, m.maxTicks());
        for (int t = 0; t < max; t++) {
            double sp = Math.hypot(vx, vy);
            if (sp < 1e-6) return null;
            double al = m.along(t, sp), g = m.vertical(t);
            double ax = vx / sp * al, ay = vy / sp * al + g;
            double nx = x + vx + 0.5 * ax, ny = y + vy + 0.5 * ay;
            if (nx >= horiz) {
                double f = (horiz - x) / Math.max(1e-9, nx - x);
                return new double[]{y + (ny - y) * f, t + f};
            }
            x = nx;
            y = ny;
            vx += ax;
            vy += ay;
            if (vx < 1e-4) return null;
        }
        return null;
    }

    private static double height(double pitch, FlightModel m, double horiz) {
        double[] r = simulate(pitch, m, horiz);
        return r == null ? Double.NEGATIVE_INFINITY : r[0];
    }

    /** @return {угол возвышения, рад; время полёта, тики} или null */
    public double[] solvePitch(double horiz, double dy, FlightModel m, double hint) {
        double lo = Double.NaN, hi = Double.NaN;          // lo: ниже цели, hi: выше цели
        if (!Double.isNaN(hint)) {
            double a1 = Math.max(minPitch, hint - HINT_WINDOW);
            double a2 = Math.min(maxPitch, hint + HINT_WINDOW);
            double h1 = height(a1, m, horiz), h2 = height(a2, m, horiz);
            if (!highArc && h1 < dy && h2 >= dy) { lo = a1; hi = a2; }
            if (highArc && h2 < dy && h1 >= dy) { lo = a2; hi = a1; }
        }
        if (Double.isNaN(lo)) {
            if (!highArc) {
                if (height(minPitch, m, horiz) >= dy) return null;          // цель ниже сектора склонения
                double prev = minPitch;
                for (double a = minPitch + SCAN_STEP; a <= maxPitch + 1e-9; a += SCAN_STEP) {
                    double aa = Math.min(a, maxPitch);
                    if (height(aa, m, horiz) >= dy) { lo = prev; hi = aa; break; }
                    prev = aa;
                }
            } else {
                if (height(maxPitch, m, horiz) >= dy) return null;
                double prev = maxPitch;
                for (double a = maxPitch - SCAN_STEP; a >= minPitch - 1e-9; a -= SCAN_STEP) {
                    double aa = Math.max(a, minPitch);
                    if (height(aa, m, horiz) >= dy) { lo = prev; hi = aa; break; }
                    prev = aa;
                }
            }
            if (Double.isNaN(lo)) return null;                              // вне досягаемости
        }
        for (int i = 0; i < BISECT; i++) {
            double mid = (lo + hi) * 0.5;
            if (height(mid, m, horiz) >= dy) hi = mid; else lo = mid;
        }
        double[] r = simulate(hi, m, horiz);
        return r == null ? null : new double[]{hi, r[1]};
    }

    /**
     * @param lagTicks   задержка: возраст замера + реакция системы до выстрела
     * @param hintPitch  прошлое решение по этой цели (радианы) или NaN
     * @param maxRange   предельная горизонтальная дальность
     */
    public Solution solve(Muzzle muzzle, TargetMotion target, double lagTicks, FlightModel m,
                          int iterations, double hintPitch, double maxRange) {
        Vec dir = target.pos().sub(muzzle.at(Vec.ZERO)).normalize();
        double ttf = target.pos().distance(muzzle.at(dir)) / Math.max(0.05, m.muzzleSpeed());
        double hint = hintPitch, pitch = Double.NaN, yaw = 0, horiz = 0;
        Vec aim = target.pos(), mz = muzzle.at(dir);
        int iters = Math.max(1, iterations);
        for (int i = 0; i < iters; i++) {
            mz = muzzle.at(dir);
            aim = target.predict(lagTicks + ttf);
            Vec d = aim.sub(mz);
            horiz = d.horizontalLength();
            if (horiz < 0.5 || horiz > maxRange) return null;
            double[] s = solvePitch(horiz, d.y(), m, hint);
            if (s == null) return null;
            pitch = s[0];
            yaw = Math.toDegrees(Math.atan2(-d.x(), d.z()));
            dir = Vec.fromAngles(yaw, Math.toDegrees(pitch));
            hint = pitch;
            boolean converged = i > 0 && Math.abs(s[1] - ttf) < 0.02;
            ttf = s[1];
            if (converged) break;
        }
        return new Solution(yaw, Math.toDegrees(pitch), ttf, aim, mz, horiz, dir);
    }

    /** Точки траектории решения в мировых координатах (каждые step тиков, включая конечную). */
    public static void trajectory(Solution sol, FlightModel m, int step, Consumer<Vec> out) {
        double pitch = Math.toRadians(sol.pitchDeg());
        Vec hdir = Vec.fromAngles(sol.yawDeg(), 0);
        double s = m.muzzleSpeed();
        double vx = s * Math.cos(pitch), vy = s * Math.sin(pitch), x = 0, y = 0;
        int end = (int) Math.ceil(sol.flightTicks());
        out.accept(sol.muzzle());
        for (int t = 0; t < end && t < m.maxTicks(); t++) {
            double sp = Math.hypot(vx, vy);
            if (sp < 1e-6) break;
            double al = m.along(t, sp), g = m.vertical(t);
            double ax = vx / sp * al, ay = vy / sp * al + g;
            x += vx + 0.5 * ax;
            y += vy + 0.5 * ay;
            vx += ax;
            vy += ay;
            if ((t + 1) % Math.max(1, step) == 0 && t + 1 < end) {
                out.accept(sol.muzzle().add(hdir.scale(x)).add(new Vec(0, y, 0)));
            }
        }
        out.accept(sol.aimPoint());
    }
}
