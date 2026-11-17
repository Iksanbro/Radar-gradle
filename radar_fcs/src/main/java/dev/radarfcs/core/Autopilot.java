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
        if (!Double.isFinite(error)) return -YAW_RATE_GAIN * yawRate;
        double want = clamp(YAW_GAIN * error, -YAW_RATE_MAX, YAW_RATE_MAX);
        return YAW_RATE_GAIN * (want - yawRate);
    }

    /**
     * Нужное ускорение корабля (мир, бл/с²): по горизонтали - держать дальность distance до цели (подход или отход не
     * быстрее maxSpeed относительно цели), в режиме с высотой - и высоту height над целью. Без дальности - ноль.
     *
     * @param ship   положение корабля, его скорость shipVel
     * @param target положение цели, её скорость targetVel
     */
    public static Vec accel(Mode mode, double distance, double height, double maxSpeed, Vec ship, Vec shipVel, Vec target, Vec targetVel) {
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

    public static double cleanSpeed(double v) {
        return Double.isFinite(v) ? clamp(v, SPEED_MIN, SPEED_MAX) : 20;
    }
}
