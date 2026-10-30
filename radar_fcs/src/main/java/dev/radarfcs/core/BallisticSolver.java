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
    private boolean discrete = true;

    public BallisticSolver(double minPitchDeg, double maxPitchDeg, boolean highArc) {
        this.minPitch = Math.toRadians(Math.min(minPitchDeg, maxPitchDeg));
        this.maxPitch = Math.toRadians(Math.max(minPitchDeg, maxPitchDeg));
        this.highArc = highArc;
    }

    /**
     * Потиковый перехват (по умолчанию). В Minecraft попадание проверяется раз в тик: отрезок, который снаряд
     * прошёл за тик, против цели в её положении на конец этого тика. Поэтому целимся не в точку непрерывной
     * встречи, а в положение цели на конец того тика, в котором снаряд проходит эту точку.
     * Для целей, проходящих за тик больше своего размера, разница решает, будет ли попадание.
     */
    public BallisticSolver discrete(boolean on) {
        this.discrete = on;
        return this;
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
     * Решение с упреждением: время полёта t такое, что снаряд долетает до позиции цели через lag + t тиков.
     * Корень g(t) = полёт(цель(lag + t)) - t ищется методом секущих (сходится и для целей, чья скорость
     * сравнима со скоростью снаряда, где простая итерация колеблется).
     *
     * @param lagTicks   через сколько тиков после замера цели снаряд появится у дульного среза
     * @param hintPitch  прошлое решение по этой цели (радианы) или NaN
     * @param maxRange   предельная горизонтальная дальность
     */
    public Solution solve(Muzzle muzzle, TargetPath target, double lagTicks, FlightModel m,
                          int iterations, double hintPitch, double maxRange) {
        Vec dir = target.pos().sub(muzzle.at(Vec.ZERO)).normalize();
        double t = target.pos().distance(muzzle.at(dir)) / Math.max(0.05, m.muzzleSpeed());
        double hint = hintPitch;
        double tPrev = Double.NaN, gPrev = Double.NaN;
        Solution best = null;
        double bestG = Double.MAX_VALUE;
        int iters = Math.max(2, iterations);
        for (int i = 0; i < iters; i++) {
            Vec mz = muzzle.at(dir);
            Vec aim = target.predict(lagTicks + t);
            Vec d = aim.sub(mz);
            double horiz = d.horizontalLength();
            if (horiz < 0.5 || horiz > maxRange) return null;
            double[] s = solvePitch(horiz, d.y(), m, hint);
            if (s == null) return null;
            double pitch = s[0];
            double yaw = Math.toDegrees(Math.atan2(-d.x(), d.z()));
            dir = Vec.fromAngles(yaw, Math.toDegrees(pitch));
            hint = pitch;
            double g = s[1] - t;
            if (Math.abs(g) < bestG) {
                bestG = Math.abs(g);
                best = new Solution(yaw, Math.toDegrees(pitch), s[1], aim, mz, horiz, dir);
            }
            if (Math.abs(g) < 0.01) break;
            double next = s[1];                                   // простая итерация
            if (!Double.isNaN(gPrev) && Math.abs(g - gPrev) > 1e-9) {
                double sec = t - g * (t - tPrev) / (g - gPrev);   // секущая
                if (sec > 0 && sec < 4.0 * m.maxTicks()) next = sec;
            }
            tPrev = t;
            gPrev = g;
            t = next;
        }
        // не сошлось (цель уходит быстрее снаряда) - решения нет
        if (best == null || bestG >= 1.0) return null;
        return discrete ? snapshot(best, muzzle, target, lagTicks, m, maxRange) : best;
    }

    /**
     * Потиковое решение: точка прицеливания - положение цели на конец тика k, причём снаряд проходит её
     * в течение этого тика (время полёта в (k-1, k]).
     */
    private Solution snapshot(Solution cont, Muzzle muzzle, TargetPath target, double lagTicks, FlightModel m, double maxRange) {
        int k = Math.max(1, (int) Math.ceil(cont.flightTicks() - 1e-6));
        Vec dir = cont.direction();
        double hint = Math.toRadians(cont.pitchDeg());
        Solution best = null;
        double bestViolation = Double.MAX_VALUE;
        java.util.Set<Integer> tried = new java.util.HashSet<>();
        for (int j = 0; j < 10; j++) {
            Vec q = target.predict(lagTicks + k);
            Vec mz = muzzle.at(dir);
            Vec d = q.sub(mz);
            double horiz = d.horizontalLength();
            if (horiz < 0.5 || horiz > maxRange) break;
            double[] s = solvePitch(horiz, d.y(), m, hint);
            if (s == null) break;
            double yaw = Math.toDegrees(Math.atan2(-d.x(), d.z()));
            Vec nd = Vec.fromAngles(yaw, Math.toDegrees(s[0]));
            boolean muzzleMoved = nd.angleTo(dir) > 0.05;
            dir = nd;
            hint = s[0];
            if (muzzleMoved && j < 9) continue;                            // уточнить точку вылета
            // время полёта должно попасть в (k-1, k]: снаряд проходит точку в тике k
            double violation = Math.max(0, s[1] - k) + Math.max(0, (k - 1) - s[1]);
            if (violation < bestViolation) {
                bestViolation = violation;
                best = new Solution(yaw, Math.toDegrees(s[0]), s[1], q, mz, horiz, dir);
            }
            if (violation < 1e-6) return best;
            tried.add(k);
            int next = s[1] > k ? k + 1 : k - 1;
            // цель приближается так быстро, что подходящего тика нет (проскок между тиками): берём ближайший
            if (next < 1 || tried.contains(next)) break;
            k = next;
        }
        return best != null ? best : cont;
    }

    /**
     * Промах выстрела: наименьшее расстояние снаряд-цель (до центра цели или до её габарита), тик сближения,
     * вектор от центра цели к снаряду в этот момент и скорость сближения (относительная скорость, блоков/тик).
     */
    public record Miss(double distance, double tick, Vec relative, double closing) {}

    public static Miss miss(Vec launch, Vec dir, FlightModel m, TargetPath target, double lagTicks, int maxTicks,
                            boolean discrete) {
        return miss(launch, dir, m, target, lagTicks, maxTicks, discrete, null);
    }

    public static Miss miss(Vec launch, Vec dir, FlightModel m, TargetPath target, double lagTicks, int maxTicks,
                            boolean discrete, Vec half) {
        return miss(launch, dir, m, target, lagTicks, maxTicks, discrete, half, 0);
    }

    /**
     * Промах снаряда, вылетевшего из точки launch по направлению dir, по движущейся цели.
     * Снаряд считается тем же интегратором, что в CBC (тик за тиком). Потиково (как проверяет Minecraft):
     * расстояние от цели на конец тика до отрезка, пройденного снарядом за этот тик; иначе - ближайшее сближение
     * относительного движения внутри тика.
     *
     * @param lagTicks через сколько тиков после замера цели снаряд появится в launch
     * @param half     полуразмеры габарита цели по осям: промах считается до габарита (0 - отрезок его задевает);
     *                 null - до центра цели
     * @param armTicks первые столько тиков полёта снаряд не сталкивается (перехват снарядов: только что вылетевший
     *                 снаряд CBC Interception не проверяет)
     */
    public static Miss miss(Vec launch, Vec dir, FlightModel m, TargetPath target, double lagTicks, int maxTicks,
                            boolean discrete, Vec half, int armTicks) {
        Vec d = dir.normalize();
        Vec h = new Vec(d.x(), 0, d.z());
        double hl = h.length();
        h = hl < 1e-9 ? new Vec(1, 0, 0) : h.scale(1 / hl);
        double s = m.muzzleSpeed();
        double pitch = Math.asin(Math.max(-1, Math.min(1, d.y())));
        double vx = s * Math.cos(pitch), vy = s * Math.sin(pitch), x = 0, y = 0;
        Vec prevP = launch, prevT = target.predict(lagTicks);
        double best = discrete || armTicks > 0 ? Double.MAX_VALUE : gap(prevP.sub(prevT), half), bestT = 0, bestClosing = 0;
        Vec bestRel = prevP.sub(prevT);
        double prevGap = prevP.distanceSqr(prevT);
        double reach = half == null ? 0 : half.length();
        // допуск положения снаряда вдоль траектории: растёт с дальностью (ошибки скорости и сопротивления)
        double alongTol = ALONG_TOL + ALONG_TOL_PER_BLOCK * launch.distance(target.predict(lagTicks));
        int away = 0;
        int max = Math.min(Math.max(1, maxTicks), Math.max(1, m.maxTicks()));
        for (int t = 0; t < max; t++) {
            double sp = Math.hypot(vx, vy);
            if (sp < 1e-6) break;
            double al = m.along(t, sp), g = m.vertical(t);
            double ax = vx / sp * al, ay = vy / sp * al + g;
            x += vx + 0.5 * ax;
            y += vy + 0.5 * ay;
            vx += ax;
            vy += ay;
            Vec p = launch.add(h.scale(x)).add(new Vec(0, y, 0));
            Vec tp = target.predict(lagTicks + t + 1);
            // отрезок движения снаряда относительно цели за этот тик: r0 -> r1
            Vec r0 = discrete ? prevP.sub(tp) : prevP.sub(prevT), r1 = p.sub(tp), seg = r1.sub(r0);
            double l2 = seg.lengthSqr();
            // Потиково: встреча у самого конца или начала отрезка неустойчива - ошибка положения снаряда
            // вдоль траектории в доли блока переносит её на соседний тик, где цель уже в другом месте.
            // Такие точки отрезка не считаем.
            double e = discrete && l2 > 1e-12 ? Math.min(0.45, alongTol / Math.sqrt(l2)) : 0;
            double u = l2 < 1e-12 ? 1 : Math.max(e, Math.min(1 - e, -r0.dot(seg) / l2));
            Vec c = r0.add(seg.scale(u));
            double dist = c.length();
            if (t < armTicks) dist = Double.MAX_VALUE;                // снаряд ещё не взведён
            else if (half != null && dist - reach < best) {
                // расстояние до прямоугольного габарита выпукло вдоль отрезка: золотое сечение
                double lo = e, hi = 1 - e;
                for (int it = 0; it < 30; it++) {
                    double a1 = hi - (hi - lo) * 0.618034, a2 = lo + (hi - lo) * 0.618034;
                    if (gap(r0.add(seg.scale(a1)), half) <= gap(r0.add(seg.scale(a2)), half)) hi = a2;
                    else lo = a1;
                }
                u = 0.5 * (lo + hi);
                c = r0.add(seg.scale(u));
                dist = gap(c, half);
            }
            if (dist < best) {
                best = dist;
                bestT = t + u;
                bestRel = c;
                bestClosing = Math.sqrt(l2);
            }
            double gp = p.distanceSqr(tp);
            away = gp > prevGap ? away + 1 : 0;
            if (away >= 4 && t > 2) break;                         // снаряд уже удаляется от цели
            prevGap = gp;
            prevP = p;
            prevT = tp;
        }
        return new Miss(best, bestT, bestRel, bestClosing);
    }

    /** Расстояние от точки (относительно центра цели) до габарита с полуразмерами half; без габарита - до центра. */
    /** Положения снаряда на конец каждого из n тиков полёта (тот же интегратор, что в miss). */
    public static java.util.List<Vec> positions(Vec launch, Vec dir, FlightModel m, int n) {
        java.util.List<Vec> out = new java.util.ArrayList<>();
        Vec d = dir.normalize();
        Vec h = new Vec(d.x(), 0, d.z());
        double hl = h.length();
        h = hl < 1e-9 ? new Vec(1, 0, 0) : h.scale(1 / hl);
        double s = m.muzzleSpeed(), pitch = Math.asin(Math.max(-1, Math.min(1, d.y())));
        double vx = s * Math.cos(pitch), vy = s * Math.sin(pitch), x = 0, y = 0;
        out.add(launch);
        for (int t = 0; t < n; t++) {
            double sp = Math.hypot(vx, vy);
            if (sp < 1e-6) break;
            double al = m.along(t, sp), g = m.vertical(t);
            double ax = vx / sp * al, ay = vy / sp * al + g;
            x += vx + 0.5 * ax;
            y += vy + 0.5 * ay;
            vx += ax;
            vy += ay;
            out.add(launch.add(h.scale(x)).add(new Vec(0, y, 0)));
        }
        return out;
    }

    /** Допуск положения снаряда вдоль траектории в потиковом расчёте: постоянная часть и рост с дальностью, блоков. */
    static final double ALONG_TOL = 0.15, ALONG_TOL_PER_BLOCK = 0.002;

    public static double gap(Vec rel, Vec half) {
        if (half == null) return rel.length();
        double dx = Math.max(0, Math.abs(rel.x()) - half.x());
        double dy = Math.max(0, Math.abs(rel.y()) - half.y());
        double dz = Math.max(0, Math.abs(rel.z()) - half.z());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
