package dev.radarfcs.radar;

import dev.radarfcs.core.Aim;

/** Зона обзора радара в мировых углах: yaw в конвенции Minecraft, угол места > 0 вверх. */
@FunctionalInterface
public interface Coverage {
    boolean covers(double yawDeg, double elevationDeg);

    /** Весь круг по азимуту. */
    static Coverage dome(double minEl, double maxEl) {
        return (yaw, el) -> el >= minEl && el <= maxEl;
    }

    /** Сектор вокруг направления centerYaw (фазированная решётка). */
    static Coverage sector(double centerYaw, double halfAzimuth, double minEl, double maxEl) {
        return (yaw, el) -> el >= minEl && el <= maxEl && Math.abs(Aim.wrap180(yaw - centerYaw)) <= halfAzimuth;
    }

    /**
     * Дуга, которую прошёл луч вращающейся антенны: от from до to (развёрнутые углы, без переноса через 360)
     * плюс половина ширины луча с каждой стороны.
     */
    static Coverage sweep(double from, double to, double halfWidth, double minEl, double maxEl) {
        double a = Math.min(from, to) - halfWidth;
        double b = Math.max(from, to) + halfWidth;
        if (b - a >= 360) return dome(minEl, maxEl);
        double span = b - a;
        return (yaw, el) -> el >= minEl && el <= maxEl && Aim.norm360(yaw - a) <= span;
    }
}
