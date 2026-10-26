package dev.radarfcs.core;

/**
 * Движение цели для упреждения.
 *
 * @param pos          текущая позиция (точка прицеливания)
 * @param vel          скорость, блоков/тик
 * @param acc          ускорение, блоков/тик²
 * @param grounded     цель идёт по земле: вертикальное движение не экстраполируется
 * @param accelHorizon сколько тиков вперёд доверять ускорению (дальше движение равномерное)
 */
public record TargetMotion(Vec pos, Vec vel, Vec acc, boolean grounded, double accelHorizon) {

    public static TargetMotion of(Vec pos, Vec vel) {
        return new TargetMotion(pos, vel, Vec.ZERO, false, 0);
    }

    /** Позиция через t тиков: ускорение действует только accelHorizon тиков, затем скорость постоянна. */
    public Vec predict(double t) {
        if (t <= 0) return pos;
        double ta = Math.min(t, Math.max(0, accelHorizon));
        // ∫ (v + a·min(τ, ta)) dτ от 0 до t
        double accTerm = ta * t - 0.5 * ta * ta;
        Vec p = pos.add(vel.scale(t)).add(acc.scale(accTerm));
        return grounded ? new Vec(p.x(), pos.y(), p.z()) : p;
    }
}
