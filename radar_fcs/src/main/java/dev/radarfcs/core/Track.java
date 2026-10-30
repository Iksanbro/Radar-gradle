package dev.radarfcs.core;

/**
 * Трек цели. Оценка движения - взвешенный МНК по скользящему окну замеров:
 * свежие замеры весят больше, при достаточной истории оценивается и ускорение.
 * Работает с нерегулярными замерами (радар обновляет цели при проходе луча).
 */
public final class Track {
    public enum Kind { MOB, PLAYER, CONTRAPTION, SHIP, PROJECTILE, EXTERNAL }

    private static final int CAP = 24;
    /** Средняя невязка подгонки, блоков: выше - цель маневрирует, окно оценки укорачивается. */
    public static final double MANEUVER_RESIDUAL = 0.2;
    private static final int MIN_WINDOW = 6;

    public final String id;
    public final Kind kind;
    public String name;
    /** ID типа сущности (minecraft:zombie) и имя игрока - для белого и чёрного списков; null - неизвестно. */
    public String typeId, playerName;
    /** Имя с транспондера «свой» на цели (корабль, контрапшен) или null. */
    public String tagName;
    /** Оценка позиции на момент последнего замера. */
    public Vec pos;
    /** Блоков/тик. */
    public Vec vel = Vec.ZERO;
    /** Блоков/тик². */
    public Vec acc = Vec.ZERO;
    public long lastTick;
    public long firstTick;
    public int samples;
    public double hitRadius = 1;
    /** Точные полуразмеры габарита цели по осям (сущности), блоков; null - известен только hitRadius. */
    public Vec half;
    /** Объём габарита цели, блоков³ (для приоритета «крупнейшая/наименьшая»). */
    public double volume = 1;
    public boolean grounded;
    /** Невязка оценки движения (блоков): большая - манёвр, по такой цели лучше не стрелять. */
    public double residual;
    /** Скорость, подогнанная по замерам (до замены точной скоростью от источника): по ней считается невязка. */
    private Vec fitVel = Vec.ZERO;
    public boolean hostile;
    public boolean moving;
    /** Последний тик, в который цель двигалась (Long.MIN_VALUE - не двигалась ни разу). */
    public long lastMovingTick = Long.MIN_VALUE;
    public boolean friendly;
    public boolean active;
    /** Точная скорость от источника (например, физика Sable), иначе null. */
    public Vec knownVel;
    /** Произвольная ссылка для слоя совместимости (сущность, корабль...). */
    public Object handle;
    /** Снаряд-цель (перехват): снаряд большой пушки или ракета; у прочих целей null. */
    public ProjectileType munition;
    /** Прогноз полёта снаряда-цели по тикам от последнего замера (по физике CBC или по замерам), иначе null. */
    public PathMotion path;
    /**
     * Снаряд-цель: как близко его прогноз проходит от своих (батарея, свои зоны), блоков, и через сколько тиков
     * (время до удара). Бесконечность - не угрожает или не считалось.
     */
    public double threatDistance = Double.POSITIVE_INFINITY, threatTicks = Double.POSITIVE_INFINITY;

    private final long[] ht = new long[CAP];
    private final double[] hx = new double[CAP], hy = new double[CAP], hz = new double[CAP];
    private int head, size;

    Track(String id, Kind kind, String name, Vec pos, long tick) {
        this.id = id;
        this.kind = kind;
        this.name = name;
        this.pos = pos;
        this.lastTick = tick;
        this.firstTick = tick;
    }

    public double speedPerTick() {
        return grounded ? vel.horizontalLength() : vel.length();
    }

    public long observedSpan() { return lastTick - firstTick; }

    /** Прогноз для упреждения: у снаряда-цели - его путь по тикам, у прочих - движение по оценке. */
    public TargetPath predictor(double accelHorizon) {
        return path != null ? path : motion(accelHorizon);
    }

    public TargetMotion motion(double accelHorizon) {
        Vec v = grounded ? new Vec(vel.x(), 0, vel.z()) : vel;
        Vec a = grounded ? new Vec(acc.x(), 0, acc.z()) : acc;
        return new TargetMotion(pos, v, a, grounded, accelHorizon);
    }

    void reset(Vec p, long tick) {
        size = 0;
        head = 0;
        samples = 0;
        vel = Vec.ZERO;
        acc = Vec.ZERO;
        firstTick = tick;
        add(p, tick);
    }

    void add(Vec p, long tick) {
        ht[head] = tick;
        hx[head] = p.x();
        hy[head] = p.y();
        hz[head] = p.z();
        head = (head + 1) % CAP;
        if (size < CAP) size++;
        samples++;
        lastTick = tick;
        pos = p;
    }

    /**
     * Пересчитать оценку по окну последних windowTicks тиков. Если модель плохо описывает замеры
     * (резкий манёвр, разворот), окно укорачивается: оценка быстрее отражает новое движение.
     */
    void fit(int windowTicks, double maxAccel) {
        int w = windowTicks;
        while (true) {
            fitWindow(w, maxAccel);
            residual = residual(w);
            if (residual <= MANEUVER_RESIDUAL || w <= MIN_WINDOW) return;
            w = Math.max(MIN_WINDOW, w / 2);
        }
    }

    /** Взвешенная средняя невязка замеров окна относительно текущей оценки (у наземных целей - по горизонтали). */
    private double residual(int windowTicks) {
        return residualOf(pos, fitVel, acc, windowTicks);
    }

    private double residualOf(Vec p0, Vec v, Vec a, int windowTicks) {
        int last = (head - 1 + CAP) % CAP;
        long t0 = ht[last];
        double tau = Math.max(2.0, windowTicks * 0.5), sw = 0, sr = 0;
        int n = 0;
        for (int k = 0; k < size; k++) {
            int i = (last - k + CAP) % CAP;
            double dt = ht[i] - t0;
            if (-dt > windowTicks && n >= 2) break;
            double w = Math.exp(dt / tau);
            double ex = hx[i] - (p0.x() + v.x() * dt + 0.5 * a.x() * dt * dt);
            double ey = grounded ? 0 : hy[i] - (p0.y() + v.y() * dt + 0.5 * a.y() * dt * dt);
            double ez = hz[i] - (p0.z() + v.z() * dt + 0.5 * a.z() * dt * dt);
            sr += w * (ex * ex + ey * ey + ez * ez);
            sw += w;
            n++;
        }
        return n < 3 || sw <= 0 ? 0 : Math.sqrt(sr / sw);
    }

    /**
     * Взвешенный МНК по окну. Считаются две модели - равномерное движение и движение с ускорением;
     * ускорение берётся, только если оно заметно лучше объясняет замеры (иначе это шум, а не манёвр:
     * экстраполяция шумового ускорения на время полёта снаряда уводит прицел на блоки).
     */
    private void fitWindow(int windowTicks, double maxAccel) {
        int last = (head - 1 + CAP) % CAP;
        long t0 = ht[last];
        double bx = hx[last], by = hy[last], bz = hz[last];
        double tau = Math.max(2.0, windowTicks * 0.5);
        double s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0;
        double rx0 = 0, rx1 = 0, rx2 = 0, ry0 = 0, ry1 = 0, ry2 = 0, rz0 = 0, rz1 = 0, rz2 = 0;
        int n = 0;
        long minT = t0;
        for (int k = 0; k < size; k++) {
            int i = (last - k + CAP) % CAP;
            double dt = ht[i] - t0;                     // <= 0
            if (-dt > windowTicks && n >= 2) break;
            double w = Math.exp(dt / tau);
            double dx = hx[i] - bx, dy = hy[i] - by, dz = hz[i] - bz;
            s0 += w; s1 += w * dt; s2 += w * dt * dt; s3 += w * dt * dt * dt; s4 += w * dt * dt * dt * dt;
            rx0 += w * dx; rx1 += w * dx * dt; rx2 += w * dx * dt * dt;
            ry0 += w * dy; ry1 += w * dy * dt; ry2 += w * dy * dt * dt;
            rz0 += w * dz; rz1 += w * dz * dt; rz2 += w * dz * dt * dt;
            minT = ht[i];
            n++;
        }
        long span = t0 - minT;
        double det = s0 * s2 - s1 * s1;
        if (n < 2 || span <= 0 || Math.abs(det) < 1e-12) {
            fitVel = Vec.ZERO;
            vel = knownVel != null ? knownVel : Vec.ZERO;
            acc = Vec.ZERO;
            return;
        }
        // равномерное движение
        double vx = (s0 * rx1 - s1 * rx0) / det, vy = (s0 * ry1 - s1 * ry0) / det, vz = (s0 * rz1 - s1 * rz0) / det;
        Vec cvPos = new Vec(bx + (rx0 - vx * s1) / s0, by + (ry0 - vy * s1) / s0, bz + (rz0 - vz * s1) / s0);
        Vec cvVel = new Vec(vx, vy, vz);
        Vec p = cvPos, v = cvVel, a = Vec.ZERO;
        if (n >= 5 && span >= 6) {
            // с ускорением: базис (1, t, t²/2), 3x3 система нормальных уравнений
            double[][] m = {
                    {s0, s1, s2 / 2},
                    {s1, s2, s3 / 2},
                    {s2 / 2, s3 / 2, s4 / 4}};
            double[] px = solve3(m, rx0, rx1, rx2 / 2);
            double[] py = solve3(m, ry0, ry1, ry2 / 2);
            double[] pz = solve3(m, rz0, rz1, rz2 / 2);
            if (px != null && py != null && pz != null) {
                Vec caPos = new Vec(bx + px[0], by + py[0], bz + pz[0]);
                Vec caVel = new Vec(px[1], py[1], pz[1]);
                Vec caAcc = new Vec(px[2], py[2], pz[2]);
                double al = caAcc.length();
                if (al > maxAccel) caAcc = caAcc.scale(maxAccel / al);
                double rcv = residualOf(cvPos, cvVel, Vec.ZERO, windowTicks);
                double rca = residualOf(caPos, caVel, caAcc, windowTicks);
                if (rcv > 0.05 && rca < 0.6 * rcv) {
                    p = caPos;
                    v = caVel;
                    a = caAcc;
                }
            }
        }
        pos = p;
        fitVel = v;
        acc = a;
        vel = knownVel != null ? knownVel : v;
    }

    private static double[] solve3(double[][] a, double b0, double b1, double b2) {
        double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
                - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
        if (Math.abs(det) < 1e-9) return null;
        double d0 = b0 * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
                - a[0][1] * (b1 * a[2][2] - a[1][2] * b2)
                + a[0][2] * (b1 * a[2][1] - a[1][1] * b2);
        double d1 = a[0][0] * (b1 * a[2][2] - a[1][2] * b2)
                - b0 * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * b2 - b1 * a[2][0]);
        double d2 = a[0][0] * (a[1][1] * b2 - b1 * a[2][1])
                - a[0][1] * (a[1][0] * b2 - b1 * a[2][0])
                + b0 * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
        return new double[]{d0 / det, d1 / det, d2 / det};
    }
}
