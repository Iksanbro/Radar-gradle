package dev.radarfcs.core;

/** Прогноз положения цели: точка сейчас и через t тиков (t - вещественное, внутри тика - по прямой). */
public interface TargetPath {
    Vec pos();

    Vec predict(double t);

    /** Тот же прогноз относительно платформы, движущейся со скоростью v (блоков/тик): стрельба с корабля. */
    default TargetPath relativeTo(Vec v) {
        if (v.lengthSqr() == 0) return this;
        TargetPath base = this;
        return new TargetPath() {
            @Override
            public Vec pos() { return base.pos(); }

            @Override
            public Vec predict(double t) { return base.predict(t).addScaled(v, -Math.max(0, t)); }
        };
    }
}
