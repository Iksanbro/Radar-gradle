package dev.radarfcs.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Выбор цели одной пушки по настройкам вычислителя: уровень приоритета типа цели, затем режим приоритета
 * (ближайшая, ранняя, дальняя...), удержание текущей цели, переключение по приоритету и по правилу
 * (после N выстрелов, через время, при остановке). Цели, от которых ушли по правилу, идут в конец
 * по очереди - так огонь обходит все цели по кругу.
 */
public final class TargetPicker {

    /** Состояние выбора цели одной пушки. */
    public static final class State {
        public String currentId;
        public long acquiredTick;
        public int shotsAtCurrent;
        /** Цели, от которых ушли по правилу переключения: id -> тик ухода. */
        final Map<String, Long> left = new HashMap<>();

        /** Цель сменилась: счётчики правила заново. */
        public void acquire(String id, long now) {
            if (id.equals(currentId)) return;
            currentId = id;
            acquiredTick = now;
            shotsAtCurrent = 0;
        }

        public void clear() {
            currentId = null;
            shotsAtCurrent = 0;
        }

        public boolean wasLeft(String id) { return left.containsKey(id); }
    }

    /** Сколько помнить цели, от которых ушли по правилу, тиков. */
    static final long LEFT_MEMORY = 600;

    private TargetPicker() {}

    /**
     * @param cands       подходящие цели (уже отфильтрованы по типу, скорости, движению, сбросу)
     * @param switchRatio насколько цель того же уровня должна быть лучше текущей, чтобы на неё перейти (0.85)
     * @param designated  цель, назначенная с монитора (вне очереди), или null
     * @return цели в порядке предпочтения
     */
    public static List<Track> order(List<Track> cands, Vec pivot, EngageSettings s, State st, long now,
                                    double switchRatio, String designated) {
        st.left.values().removeIf(t -> now - t > LEFT_MEMORY);
        Track cur = null;
        for (Track t : cands) if (t.id.equals(st.currentId)) cur = t;

        // правило переключения: уйти с текущей, если есть другая цель
        if (cur != null && cands.size() > 1 && switchDue(cur, s, st, now)) {
            st.left.put(cur.id, now);
            cur = null;
        } else if (cur != null && cands.size() == 1 && switchDue(cur, s, st, now)) {
            st.acquiredTick = now;                         // другой цели нет: остаёмся, правило - заново
            st.shotsAtCurrent = 0;
        }

        Comparator<Track> byPriority = Comparator.<Track>comparingInt(t -> -s.level(t))
                .thenComparing(sameLevel(s, pivot));
        List<Track> fresh = new ArrayList<>(), visited = new ArrayList<>();
        for (Track t : cands) (st.left.containsKey(t.id) && t != cur ? visited : fresh).add(t);
        fresh.sort(byPriority);
        visited.sort(Comparator.comparingLong(t -> st.left.get(t.id)));    // давно оставленные - раньше
        List<Track> out = new ArrayList<>(fresh);
        out.addAll(visited);

        // удержание текущей цели
        if (cur != null && !out.isEmpty() && out.get(0) != cur) {
            Track best = out.get(0);
            boolean switchNow = s.switchByPriority && betterThan(best, cur, s, pivot, switchRatio);
            if (!switchNow) {
                out.remove(cur);
                out.add(0, cur);
            }
        }
        // назначенная с монитора - вне очереди
        if (designated != null) {
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).id.equals(designated)) {
                    out.add(0, out.remove(i));
                    break;
                }
            }
        }
        return out;
    }

    /** Сработало ли правило переключения по текущей цели. */
    static boolean switchDue(Track cur, EngageSettings s, State st, long now) {
        return switch (s.switchMode) {
            case NONE -> false;
            case AFTER_SHOTS -> st.shotsAtCurrent >= s.switchShots;
            case AFTER_TIME -> now - st.acquiredTick >= s.switchSeconds * 20L;
            case ON_STOP -> s.isStatic(cur);
        };
    }

    /** Цель a настолько лучше текущей b, что на неё стоит перейти. */
    static boolean betterThan(Track a, Track b, EngageSettings s, Vec pivot, double ratio) {
        int la = s.level(a), lb = s.level(b);
        if (la != lb) return la > lb;
        boolean ia = byImpact(a, s), ib = byImpact(b, s);
        if (ia != ib) return ia;
        // снаряды: переходить на тот, что долетит заметно раньше
        if (ia) return a.threatTicks < b.threatTicks * ratio - 1;
        return switch (s.priority) {
            case NEAREST -> a.pos.distance(pivot) < b.pos.distance(pivot) * ratio;
            case FARTHEST -> a.pos.distance(pivot) * ratio > b.pos.distance(pivot);
            case EARLIEST -> a.firstTick < b.firstTick;
            case LARGEST -> a.volume * ratio > b.volume;
            case SMALLEST -> a.volume < b.volume * ratio;
            case FASTEST -> a.speedPerTick() * ratio > b.speedPerTick() + 0.01;
            case SLOWEST -> a.speedPerTick() < b.speedPerTick() * ratio - 0.01;
        };
    }

    /** Снаряд-цель, который при равном уровне выбирается по времени до удара. */
    static boolean byImpact(Track t, EngageSettings s) {
        return s.impactFirst && t.kind == Track.Kind.PROJECTILE;
    }

    /**
     * Порядок при равном уровне: снаряды-цели (если «первым - кто раньше долетит») - впереди остальных и между
     * собой по времени до удара; остальные - по режиму приоритета.
     */
    static Comparator<Track> sameLevel(EngageSettings s, Vec pivot) {
        Comparator<Track> m = metric(s.priority, pivot);
        return (a, b) -> {
            boolean ia = byImpact(a, s), ib = byImpact(b, s);
            if (ia != ib) return ia ? -1 : 1;
            if (ia) {
                int c = Double.compare(a.threatTicks, b.threatTicks);
                return c != 0 ? c : Double.compare(a.pos.distance(pivot), b.pos.distance(pivot));
            }
            return m.compare(a, b);
        };
    }

    static Comparator<Track> metric(EngageSettings.Priority p, Vec pivot) {
        return switch (p) {
            case NEAREST -> Comparator.comparingDouble(t -> t.pos.distance(pivot));
            case FARTHEST -> Comparator.comparingDouble(t -> -t.pos.distance(pivot));
            case EARLIEST -> Comparator.<Track>comparingLong(t -> t.firstTick).thenComparingDouble(t -> t.pos.distance(pivot));
            case LARGEST -> Comparator.comparingDouble(t -> -t.volume);
            case SMALLEST -> Comparator.comparingDouble(t -> t.volume);
            case FASTEST -> Comparator.comparingDouble(t -> -t.speedPerTick());
            case SLOWEST -> Comparator.comparingDouble(Track::speedPerTick);
        };
    }
}
