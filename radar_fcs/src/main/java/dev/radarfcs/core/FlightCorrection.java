package dev.radarfcs.core;

import java.util.List;

/**
 * Поправки к модели полёта, выученные по реальным выстрелам.
 * После выстрела за снарядом следят несколько тиков: из приращений скорости
 * a = v(t+1) - v(t) выделяются ускорение вдоль скорости и вертикальное ускорение.
 * Их отличие от базовой модели копится по тикам. Так учитываются тяга ракет,
 * нестандартная гравитация и сопротивление снарядов аддонов, а также реальная начальная скорость.
 */
public final class FlightCorrection {
    public static final int MAX_TICKS = 240;
    /** Наибольшая правдоподобная тяга, блоков/тик²: больше - уже не полёт, а сбой наблюдения. */
    private static final double MAX_THRUST = 4.0;

    private final double[] dAlong = new double[MAX_TICKS];
    private final double[] dVert = new double[MAX_TICKS];
    private final int[] count = new int[MAX_TICKS];
    private int learnedTicks;
    private double speedRatio = 1.0;
    private int speedSamples;
    private int shots;

    public int shots() { return shots; }

    public double speedRatio() { return speedRatio; }

    public int learnedTicks() { return learnedTicks; }

    /** Сколько тиков полёта уже изучено (для проверки, что поправки покрывают время полёта). */
    public boolean covers(double flightTicks) { return learnedTicks >= Math.min(MAX_TICKS, flightTicks); }

    /**
     * Учесть наблюдённый полёт.
     *
     * @param base       модель, по которой стреляли
     * @param velocities скорость снаряда в начале каждого тика полёта (velocities[0] = на вылете)
     * @param smoothing  вес нового наблюдения после первых выстрелов (0..1)
     * @return сколько тиков удалось учесть
     */
    public int learn(FlightModel base, List<Vec> velocities, double smoothing) {
        if (velocities.isEmpty()) return 0;
        double s0 = velocities.get(0).length();
        if (base.muzzleSpeed() > 1e-6 && s0 > 1e-6) {
            double ratio = s0 / base.muzzleSpeed();
            double w = speedSamples < 3 ? 1.0 / (speedSamples + 1) : smoothing;
            speedRatio += (ratio - speedRatio) * w;
            speedSamples++;
        }
        int used = 0;
        double prevAlong = 0;
        for (int t = 0; t + 1 < velocities.size() && t < MAX_TICKS; t++) {
            Vec v = velocities.get(t);
            Vec a = velocities.get(t + 1).sub(v);
            double s = v.length();
            if (s < 1e-4) break;
            Vec u = v.scale(1.0 / s);
            double c = u.y();
            double det = 1 - c * c;
            if (det < 0.02) break;                              // почти вертикально: разложение неустойчиво
            double au = a.dot(u);
            double along = (au - c * a.y()) / det;
            double vert = (a.y() - c * au) / det;
            double rAlong = along - base.along(t, s);
            double rVert = vert - base.vertical(t);
            // Попадание (снаряд пробил цель и затормозил), рикошет: сильное и внезапное торможение или боковой скачок -
            // дальше данные не про свободный полёт. Ровное отличие (сопротивление снаряда аддона) и разгон вперёд
            // (тяга ракеты, в том числе с медленным стартом) - учатся.
            double drop = 0.3 + 0.05 * s;
            if ((rAlong < -drop && rAlong < prevAlong - drop) || rAlong > MAX_THRUST || Math.abs(rVert) > 0.5) break;
            prevAlong = rAlong;
            double w = count[t] < 3 ? 1.0 / (count[t] + 1) : smoothing;
            dAlong[t] += (rAlong - dAlong[t]) * w;
            dVert[t] += (rVert - dVert[t]) * w;
            count[t]++;
            used++;
        }
        learnedTicks = Math.max(learnedTicks, used);
        shots++;
        return used;
    }

    public void reset() {
        java.util.Arrays.fill(dAlong, 0);
        java.util.Arrays.fill(dVert, 0);
        java.util.Arrays.fill(count, 0);
        learnedTicks = 0;
        speedRatio = 1.0;
        speedSamples = 0;
        shots = 0;
    }

    /** Модель с поправками. Если наблюдений нет, возвращает базовую. */
    public FlightModel apply(FlightModel base) {
        if (shots == 0) return base;
        final double speed = base.muzzleSpeed() * speedRatio;
        final int learned = learnedTicks;
        // за пределами изученного участка продолжаем последнюю поправку тяги только если она была малой
        final double tailVert = learned > 0 ? dVert[learned - 1] : 0;
        return new FlightModel() {
            @Override public double muzzleSpeed() { return speed; }
            @Override public double along(int tick, double s) {
                double a = base.along(tick, s);
                return tick < learned ? a + dAlong[tick] : a;
            }
            @Override public double vertical(int tick) {
                double g = base.vertical(tick);
                return tick < learned ? g + dVert[tick] : g + tailVert;
            }
            @Override public int maxTicks() { return base.maxTicks(); }
        };
    }
}
