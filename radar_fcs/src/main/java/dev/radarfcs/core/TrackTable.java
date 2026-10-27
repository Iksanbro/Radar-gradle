package dev.radarfcs.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Таблица треков: приём замеров, оценка движения, отсев неподвижных целей с гистерезисом. */
public final class TrackTable {
    /** Блоков/тик: скачок быстрее считается телепортом, история сбрасывается. */
    private static final double TELEPORT_SPEED = 12.0;
    /** Ниже этой доли порога скорости цель снова считается неподвижной. */
    private static final double HYSTERESIS = 0.6;

    private final Map<String, Track> tracks = new HashMap<>();

    public record Settings(int windowTicks, double maxAccel) {
        public static final Settings DEFAULT = new Settings(16, 0.12);
    }

    /**
     * Принять замер.
     *
     * @param knownVel точная скорость от источника (блоков/тик) или null
     * @return трек
     */
    public Track observe(String id, Track.Kind kind, String name, Vec pos, double hitRadius, long tick,
                         boolean grounded, Vec knownVel, Settings s) {
        Track t = tracks.get(id);
        if (t == null) {
            t = new Track(id, kind, name, pos, tick);
            t.add(pos, tick);
            t.hitRadius = hitRadius;
            t.grounded = grounded;
            t.knownVel = knownVel;
            if (knownVel != null) t.vel = knownVel;
            tracks.put(id, t);
            return t;
        }
        t.name = name;
        t.hitRadius = hitRadius;
        t.grounded = grounded;
        t.knownVel = knownVel;
        long dt = tick - t.lastTick;
        if (dt <= 0) return t;                              // замер не новее: нечего добавлять
        double jump = pos.distance(t.pos) / dt;
        if (jump > TELEPORT_SPEED) {
            t.reset(pos, tick);
        } else {
            t.add(pos, tick);
        }
        t.fit(s.windowTicks(), s.maxAccel());
        return t;
    }

    public Track get(String id) { return tracks.get(id); }

    public void remove(String id) { tracks.remove(id); }

    public void prune(long tick, long timeoutTicks) {
        tracks.values().removeIf(t -> tick - t.lastTick > timeoutTicks);
    }

    /**
     * Классификация: подвижность с гистерезисом, свой/чужой, активность.
     * Активная цель - чужая, движется не медленнее minSpeed, наблюдалась достаточно долго и в пределах дальности.
     */
    public void classify(double minSpeedPerTick, int minSamples, Predicate<Track> isFriendly,
                         Predicate<Track> isEligible, Vec origin, double minRange, double maxRange) {
        classify(minSpeedPerTick, minSamples, isFriendly, isEligible, origin, minRange, maxRange, t -> t.moving);
    }

    /**
     * @param motionOk годится ли цель по движению (флаг moving уже выставлен): обычно - только движущиеся;
     *                 вычислитель может брать и стоящие, или только стоящие
     */
    public void classify(double minSpeedPerTick, int minSamples, Predicate<Track> isFriendly,
                         Predicate<Track> isEligible, Vec origin, double minRange, double maxRange, Predicate<Track> motionOk) {
        for (Track t : tracks.values()) {
            double s = t.speedPerTick();
            if (s >= minSpeedPerTick) t.moving = true;
            else if (s < minSpeedPerTick * HYSTERESIS) t.moving = false;
            t.friendly = isFriendly.test(t);
            double d = t.pos.distance(origin);
            t.active = !t.friendly && motionOk.test(t) && t.samples >= minSamples && t.observedSpan() >= 3
                    && d >= minRange && d <= maxRange && isEligible.test(t);
        }
    }

    public List<Track> active() {
        List<Track> out = new ArrayList<>();
        for (Track t : tracks.values()) if (t.active) out.add(t);
        return out;
    }

    public static List<Track> sortedByDistance(Collection<Track> list, Vec from) {
        List<Track> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparingDouble(t -> t.pos.distance(from)));
        return copy;
    }

    public Collection<Track> all() { return tracks.values(); }

    public int size() { return tracks.size(); }

    public void clear() { tracks.clear(); }
}
