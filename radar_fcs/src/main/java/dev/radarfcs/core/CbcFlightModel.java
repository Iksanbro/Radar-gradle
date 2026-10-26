package dev.radarfcs.core;

/**
 * Модель снаряда Create Big Cannons.
 * В CBC за тик: a = -v̂·min(k·|v|^n, |v|) + (0, g, 0); pos += v + a/2; v += a
 * (n = 2 при квадратичном сопротивлении, иначе 1).
 * Для ракет (CBC Advanced Technologies) добавлена фаза тяги: thrust вдоль v̂
 * в течение burnTicks, гравитация в фазах тяги и инерции умножается на свои коэффициенты.
 *
 * @param speed        начальная скорость, блоков/тик
 * @param gravity      гравитация, блоков/тик² (отрицательная)
 * @param drag         коэффициент сопротивления k
 * @param quadratic    квадратичное сопротивление
 * @param maxTicks     время жизни снаряда
 * @param thrust       ускорение тяги, блоков/тик² (0 = нет тяги)
 * @param burnTicks    длительность тяги
 * @param burnGravity  множитель гравитации во время тяги
 * @param coastGravity множитель гравитации после тяги
 */
public record CbcFlightModel(double speed, double gravity, double drag, boolean quadratic, int maxTicks,
                             double thrust, int burnTicks, double burnGravity, double coastGravity)
        implements FlightModel {

    public static CbcFlightModel ballistic(double speed, double gravity, double drag, boolean quadratic, int maxTicks) {
        return new CbcFlightModel(speed, gravity, drag, quadratic, maxTicks, 0, 0, 1, 1);
    }

    @Override
    public double muzzleSpeed() { return speed; }

    @Override
    public double along(int tick, double s) {
        double d = drag * (quadratic ? s * s : s);
        double a = -Math.min(d, s);
        if (tick < burnTicks) a += thrust;
        return a;
    }

    @Override
    public double vertical(int tick) {
        if (burnTicks <= 0) return gravity;
        return gravity * (tick < burnTicks ? burnGravity : coastGravity);
    }

    public CbcFlightModel withSpeed(double s) {
        return new CbcFlightModel(s, gravity, drag, quadratic, maxTicks, thrust, burnTicks, burnGravity, coastGravity);
    }

    public CbcFlightModel withMaxTicks(int t) {
        return new CbcFlightModel(speed, gravity, drag, quadratic, t, thrust, burnTicks, burnGravity, coastGravity);
    }
}
