package dev.radarfcs.core;

/**
 * Прогноз полёта снаряда-цели по тикам: points[k] - положение через k тиков после замера (points[0] - сейчас).
 * Внутри тика снаряд летит по прямой (так Minecraft его и двигает, и так проверяет столкновение снарядов
 * CBC Interception), после последней точки - равномерно с последней скоростью.
 */
public record PathMotion(Vec[] points) implements TargetPath {
    public PathMotion {
        if (points == null || points.length == 0) throw new IllegalArgumentException("empty path");
    }

    @Override
    public Vec pos() { return points[0]; }

    @Override
    public Vec predict(double t) {
        if (t <= 0 || points.length == 1) return points[0];
        int n = points.length - 1;
        if (t >= n) {
            Vec last = points[n], v = last.sub(points[n - 1]);
            return last.addScaled(v, t - n);
        }
        int k = (int) Math.floor(t);
        return points[k].lerp(points[k + 1], t - k);
    }

    /** Скорость на k-м тике пути, блоков/тик. */
    public Vec velocity(int k) {
        int n = points.length - 1;
        if (n == 0) return Vec.ZERO;
        int i = Math.max(0, Math.min(n - 1, k));
        return points[i + 1].sub(points[i]);
    }
}
