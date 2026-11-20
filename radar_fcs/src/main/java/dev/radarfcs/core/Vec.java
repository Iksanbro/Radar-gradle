package dev.radarfcs.core;

/** Неизменяемый 3D-вектор. Ядро не зависит от Minecraft. */
public record Vec(double x, double y, double z) {
    public static final Vec ZERO = new Vec(0, 0, 0);
    public static final Vec UP = new Vec(0, 1, 0);

    public Vec add(Vec o) { return new Vec(x + o.x, y + o.y, z + o.z); }
    public Vec sub(Vec o) { return new Vec(x - o.x, y - o.y, z - o.z); }
    public Vec scale(double k) { return new Vec(x * k, y * k, z * k); }
    public Vec addScaled(Vec o, double k) { return new Vec(x + o.x * k, y + o.y * k, z + o.z * k); }
    public double dot(Vec o) { return x * o.x + y * o.y + z * o.z; }
    public Vec cross(Vec o) { return new Vec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
    public double lengthSqr() { return dot(this); }
    public double length() { return Math.sqrt(dot(this)); }
    public double horizontalLength() { return Math.hypot(x, z); }
    public double distance(Vec o) { return sub(o).length(); }
    public double distanceSqr(Vec o) { return sub(o).lengthSqr(); }
    public Vec horizontal() { return new Vec(x, 0, z); }

    public Vec normalize() {
        double l = length();
        return l < 1e-12 ? ZERO : scale(1.0 / l);
    }

    public Vec lerp(Vec o, double a) {
        return new Vec(x + (o.x - x) * a, y + (o.y - y) * a, z + (o.z - z) * a);
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /** Направление по углам в конвенции Minecraft: yaw 0 = юг (+Z), 90 = запад (-X); pitch > 0 = вверх. */
    public static Vec fromAngles(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double c = Math.cos(pitch);
        return new Vec(-Math.sin(yaw) * c, Math.sin(pitch), Math.cos(yaw) * c);
    }

    /** Yaw в конвенции Minecraft, градусы (-180..180]. */
    public double yawDeg() { return Math.toDegrees(Math.atan2(-x, z)); }

    /** Угол возвышения, градусы. */
    public double pitchDeg() { return Math.toDegrees(Math.atan2(y, horizontalLength())); }

    /** Угол между векторами, градусы. */
    public double angleTo(Vec o) {
        double l = length() * o.length();
        if (l < 1e-12) return 180;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(o) / l))));
    }
}
