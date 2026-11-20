package dev.radarfcs.core;

/**
 * Автопилот моста Flight Control (чистая математика, без Minecraft): поворот корпуса к цели и удержание дальности
 * (и высоты над целью). Все скорости - в блоках в секунду, углы - в радианах, мировые оси Minecraft (+Y - вверх).
 */
public final class Autopilot {
    /** Режим дальности: выключен, только дальность (по горизонтали), дальность и высота над целью. */
    public enum Mode {
        OFF, DISTANCE, DISTANCE_ALTITUDE;

        public String key() {
            return "radar_fcs.autopilot.mode." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Курс: ошибка угла -> желаемая угловая скорость (1/с), предел угловой скорости (рад/с), отклик скорости (1/с). */
    public static final double YAW_GAIN = 1.5, YAW_RATE_MAX = 0.8, YAW_RATE_GAIN = 3.0;
    /** Дальность: ошибка (блоки) -> скорость сближения (1/с); отклик скорости (1/с); предел ускорения (бл/с²). */
    public static final double POS_GAIN = 0.5, VEL_GAIN = 2.0, ACC_MAX = 20.0;

    public static final double DISTANCE_MIN = 0, DISTANCE_MAX = 4096, HEIGHT_MIN = -1024, HEIGHT_MAX = 1024;
    public static final double SPEED_MIN = 1, SPEED_MAX = 200;
    /** Пределы крена и тангажа (градусы): по умолчанию, допустимые. */
    public static final double ROLL_DEFAULT = 30, PITCH_DEFAULT = 45, ANGLE_MIN = 1, ROLL_MAX = 80, PITCH_MAX = 89;
    /** Ошибка курса (рад), при которой крен - полный предел: меньше - пропорционально. */
    public static final double ROLL_FULL_ERROR = Math.toRadians(30);

    private Autopilot() {}

    /**
     * Ошибка курса: на сколько повернуть вокруг +Y, чтобы горизонтальная проекция forward смотрела на direction
     * (-π..π, плюс - против часовой стрелки при взгляде сверху: +X к -Z). NaN, если одно из направлений вертикально.
     */
    public static double yawError(Vec forward, Vec direction) {
        double fx = forward.x(), fz = forward.z(), dx = direction.x(), dz = direction.z();
        double fl = Math.hypot(fx, fz), dl = Math.hypot(dx, dz);
        if (fl < 1e-6 || dl < 1e-6) return Double.NaN;
        // поворот вокруг +Y: (x, z) -> (x cos + z sin, -x sin + z cos); угол от f к d - знак (f x d).y = fz*dx - fx*dz
        double cross = fz * dx - fx * dz, dot = fx * dx + fz * dz;
        return Math.atan2(cross / (fl * dl), dot / (fl * dl));
    }

    /** Нужное угловое ускорение вокруг мировой +Y (рад/с²) по ошибке курса и текущей угловой скорости вокруг +Y. */
    public static double yawAccel(double error, double yawRate) {
        return axisAccel(error, yawRate);
    }

    /** Угловое ускорение вокруг оси (рад/с²): ошибка угла -> скорость поворота (не больше предела) -> ускорение. */
    public static double axisAccel(double error, double rate) {
        if (!Double.isFinite(error)) return -YAW_RATE_GAIN * rate;
        double want = clamp(YAW_GAIN * error, -YAW_RATE_MAX, YAW_RATE_MAX);
        return YAW_RATE_GAIN * (want - rate);
    }

    /**
     * Нужное угловое ускорение корабля (мир, рад/с²) для ведения цели по включённым осям.
     * <ul>
     *   <li>рыскание - нос (по горизонтали) на цель, вокруг мировой вертикали;</li>
     *   <li>тангаж - нос вверх/вниз на угол места цели (не больше maxPitch от горизонта), вокруг поперечной оси;</li>
     *   <li>крен - в сторону поворота, как самолёт: пропорционально ошибке курса, полный maxRoll при 30° и больше;
     *       нос на цели - крыло ровно. Вокруг продольной оси.</li>
     * </ul>
     * Выключенные тангаж и крен мост держит ровно (нос по горизонту, крыло ровно) - иначе при повороте с поднятым
     * носом корабль заваливался бы по крену; выключенное рыскание - не трогает. Все три выключены - момента нет.
     *
     * @param forward нос корабля (мир), up - его «верх», w - угловая скорость (мир, рад/с), dir - направление на цель
     */
    public static Vec attitudeAccel(Vec forward, Vec up, Vec w, Vec dir, boolean yaw, boolean pitch, boolean roll,
                                    double maxPitchDeg, double maxRollDeg) {
        if (!yaw && !pitch && !roll) return Vec.ZERO;
        Vec f = forward.normalize();
        double e = yawError(f, dir);
        Vec out = Vec.ZERO;
        if (yaw) out = out.add(Vec.UP.scale(axisAccel(e, w.y())));
        Vec side = f.cross(Vec.UP);                                      // вправо от носа (по горизонтали)
        if (side.length() < 1e-6) return out;                           // нос вертикально - тангаж и крен не определены
        side = side.normalize();
        // тангаж: на угол места цели или (выключен) нос по горизонту
        double pitchNow = Math.atan2(f.y(), f.horizontalLength());
        double maxP = Math.toRadians(clamp(maxPitchDeg, ANGLE_MIN, PITCH_MAX));
        double pitchWant = !pitch || dir.lengthSqr() < 1e-12 ? 0 : clamp(Math.atan2(dir.y(), dir.horizontalLength()), -maxP, maxP);
        out = out.add(side.scale(axisAccel(pitchWant - pitchNow, w.dot(side))));    // + вокруг «вправо» - нос вверх
        // крен: в сторону поворота или (выключен) крыло ровно
        Vec level = Vec.UP.sub(f.scale(f.y())).normalize();             // «верх» без крена
        double bankNow = Math.atan2(up.dot(side), up.dot(level));        // + - крен вправо (правое крыло вниз)
        double maxR = Math.toRadians(clamp(maxRollDeg, ANGLE_MIN, ROLL_MAX));
        double bankWant = roll && Double.isFinite(e) ? -clamp(e / ROLL_FULL_ERROR, -1, 1) * maxR : 0;   // влево (e > 0) - крен влево
        out = out.add(f.scale(axisAccel(wrap(bankWant - bankNow), w.dot(f))));    // + вокруг носа - крен вправо
        return out;
    }

    /**
     * Желаемая угловая скорость корабля (мир, рад/с) для регулятора угловой скорости Flight Control: по каждой оси -
     * ошибка угла x {@link #YAW_GAIN}, не больше {@link #YAW_RATE_MAX}. Оси - как в {@link #attitudeAccel}: рыскание
     * вокруг мировой вертикали, тангаж вокруг поперечной оси, крен вокруг носа; выключенные тангаж и крен - держать
     * ровно, выключенное рыскание - 0 вокруг вертикали (курс держится). Все три выключены - ноль.
     */
    public static Vec desiredRates(Vec forward, Vec up, Vec dir, boolean yaw, boolean pitch, boolean roll,
                                   double maxPitchDeg, double maxRollDeg) {
        if (!yaw && !pitch && !roll) return Vec.ZERO;
        Vec f = forward.normalize();
        double e = yawError(f, dir);
        Vec out = yaw ? Vec.UP.scale(rate(e)) : Vec.ZERO;
        Vec side = f.cross(Vec.UP);
        if (side.length() < 1e-6) return out;
        side = side.normalize();
        double pitchNow = Math.atan2(f.y(), f.horizontalLength());
        double maxP = Math.toRadians(clamp(maxPitchDeg, ANGLE_MIN, PITCH_MAX));
        double pitchWant = !pitch || dir.lengthSqr() < 1e-12 ? 0 : clamp(Math.atan2(dir.y(), dir.horizontalLength()), -maxP, maxP);
        out = out.add(side.scale(rate(pitchWant - pitchNow)));
        Vec level = Vec.UP.sub(f.scale(f.y())).normalize();
        double bankNow = Math.atan2(up.dot(side), up.dot(level));
        double maxR = Math.toRadians(clamp(maxRollDeg, ANGLE_MIN, ROLL_MAX));
        double bankWant = roll && Double.isFinite(e) ? -clamp(e / ROLL_FULL_ERROR, -1, 1) * maxR : 0;
        return out.add(f.scale(rate(wrap(bankWant - bankNow))));
    }

    private static double rate(double error) {
        return Double.isFinite(error) ? clamp(YAW_GAIN * error, -YAW_RATE_MAX, YAW_RATE_MAX) : 0;
    }

    /**
     * Желаемая скорость корабля (мир, бл/с) для регулятора скорости Flight Control: по горизонтали - скорость цели +
     * сближение (ошибка дальности x {@link #POS_GAIN}, не больше maxSpeed), по вертикали - к высоте над целью (режим
     * «дальность и высота») или к holdY («только дальность»; NaN - вертикаль 0). Режим «выкл» - null (движением не
     * управлять).
     */
    public static Vec desiredVelocity(Mode mode, double distance, double height, double maxSpeed, Vec ship, Vec target, Vec targetVel,
                                      double holdY) {
        if (mode == Mode.OFF) return null;
        Vec r = target.sub(ship).horizontal();
        double d = r.length();
        Vec dir = d > 1e-3 ? r.scale(1 / d) : Vec.ZERO;
        double closing = clamp(POS_GAIN * (d - distance), -maxSpeed, maxSpeed);
        Vec v = targetVel.horizontal().add(dir.scale(closing));
        double vy = 0;
        if (mode == Mode.DISTANCE_ALTITUDE) vy = targetVel.y() + clamp(POS_GAIN * (target.y() + height - ship.y()), -maxSpeed, maxSpeed);
        else if (Double.isFinite(holdY)) vy = clamp(POS_GAIN * (holdY - ship.y()), -maxSpeed, maxSpeed);
        return new Vec(v.x(), vy, v.z());
    }

    /** Угол в -π..π. */
    public static double wrap(double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }

    /**
     * Нужное ускорение корабля (мир, бл/с²): по горизонтали - держать дальность distance до цели (подход или отход не
     * быстрее maxSpeed относительно цели); по вертикали - в режиме «дальность и высота» высоту height над целью, в режиме
     * «только дальность» - высоту holdY (на которой взяли цель; NaN - вертикаль не трогать). Без дальности - ноль.
     *
     * @param ship   положение корабля, его скорость shipVel
     * @param target положение цели, её скорость targetVel
     */
    public static Vec accel(Mode mode, double distance, double height, double maxSpeed, Vec ship, Vec shipVel, Vec target, Vec targetVel) {
        return accel(mode, distance, height, maxSpeed, ship, shipVel, target, targetVel, Double.NaN);
    }

    public static Vec accel(Mode mode, double distance, double height, double maxSpeed, Vec ship, Vec shipVel, Vec target, Vec targetVel,
                            double holdY) {
        if (mode == Mode.OFF) return Vec.ZERO;
        Vec r = target.sub(ship).horizontal();
        double d = r.length();
        Vec dir = d > 1e-3 ? r.scale(1 / d) : Vec.ZERO;
        double closing = clamp(POS_GAIN * (d - distance), -maxSpeed, maxSpeed);         // + к цели
        Vec want = targetVel.horizontal().add(dir.scale(closing));
        double ax = VEL_GAIN * (want.x() - shipVel.x()), az = VEL_GAIN * (want.z() - shipVel.z()), ay = 0;
        if (mode == Mode.DISTANCE_ALTITUDE) {
            double climb = clamp(POS_GAIN * (target.y() + height - ship.y()), -maxSpeed, maxSpeed);
            ay = VEL_GAIN * (targetVel.y() + climb - shipVel.y());
        } else if (Double.isFinite(holdY)) {
            double climb = clamp(POS_GAIN * (holdY - ship.y()), -maxSpeed, maxSpeed);
            ay = VEL_GAIN * (climb - shipVel.y());
        }
        Vec a = new Vec(ax, ay, az);
        double len = a.length();
        return len > ACC_MAX ? a.scale(ACC_MAX / len) : a;
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** Значения настроек в допустимых пределах (NaN - значение по умолчанию). */
    public static double cleanDistance(double v) {
        return Double.isFinite(v) ? clamp(v, DISTANCE_MIN, DISTANCE_MAX) : 48;
    }

    public static double cleanHeight(double v) {
        return Double.isFinite(v) ? clamp(v, HEIGHT_MIN, HEIGHT_MAX) : 16;
    }

    public static double cleanRoll(double v) {
        return Double.isFinite(v) ? clamp(v, ANGLE_MIN, ROLL_MAX) : ROLL_DEFAULT;
    }

    public static double cleanPitch(double v) {
        return Double.isFinite(v) ? clamp(v, ANGLE_MIN, PITCH_MAX) : PITCH_DEFAULT;
    }

    public static double cleanSpeed(double v) {
        return Double.isFinite(v) ? clamp(v, SPEED_MIN, SPEED_MAX) : 20;
    }
}
