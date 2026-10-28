package dev.radarfcs.iff;

import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.IffRule;
import dev.radarfcs.core.Vec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Загруженные транспондеры «свой» по измерениям: код, режим выделения, радиус.
 * Своими их считает только вычислитель, чьё правило (белый или чёрный список кодов) принимает код.
 * - Транспондер на сублевеле (корабль Sable / Create Aeronautics) делает своим этот сублевел в любом режиме;
 * - в режиме «радиус» он ещё и защищает всё в радиусе вокруг себя (на корабле - вокруг текущего положения).
 * Транспондеры в контрапшенах Create проверяются по блокам контрапшена (ContraptionCompat).
 * Принадлежность кораблю определяется при каждом запросе: при сборке корабля блок может загрузиться
 * в плоте раньше, чем Sable зарегистрирует сам корабль.
 */
public final class IffRegistry {
    /** Транспондер: код ("" - без кода), режим (null - по месту: на сублевеле «сублевел», иначе «радиус»), радиус. */
    public record Beacon(String code, @Nullable TransponderMode mode, int radius) {}

    /** Защитная сфера транспондера в мировых координатах. */
    public record Zone(Vec center, double radius) {
        public boolean contains(Vec p) {
            return center.sub(p).lengthSqr() <= radius * radius;
        }
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, Beacon>> MAP = new ConcurrentHashMap<>();

    private IffRegistry() {}

    public static void put(Level level, BlockPos pos, Beacon b) {
        MAP.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(pos.immutable(), b);
    }

    public static void remove(Level level, BlockPos pos) {
        Map<BlockPos, Beacon> m = MAP.get(level.dimension());
        if (m != null) m.remove(pos);
    }

    public static void clear() { MAP.clear(); }

    /** Режим транспондера с учётом места (не задан - по месту). */
    public static TransponderMode modeAt(Level level, BlockPos pos, @Nullable TransponderMode mode) {
        if (mode != null) return mode;
        return SableCompat.containingId(level, pos) != null ? TransponderMode.SUBLEVEL : TransponderMode.RADIUS;
    }

    /** Защитные сферы транспондеров в режиме «радиус», которые правило вычислителя считает своими. */
    public static List<Zone> zones(Level level, IffRule rule) {
        List<Zone> out = new ArrayList<>();
        Map<BlockPos, Beacon> m = MAP.get(level.dimension());
        if (m == null) return out;
        for (Map.Entry<BlockPos, Beacon> e : m.entrySet()) {
            Beacon b = e.getValue();
            if (!rule.accepts(b.code()) || modeAt(level, e.getKey(), b.mode()) != TransponderMode.RADIUS) continue;
            Vec3 c = SableCompat.toWorld(level, Vec3.atCenterOf(e.getKey()));
            out.add(new Zone(new Vec(c.x, c.y, c.z), b.radius()));
        }
        return out;
    }

    /** Точка внутри защитной сферы своего транспондера. */
    public static boolean protectsPoint(Level level, Vec p, IffRule rule) {
        for (Zone z : zones(level, rule)) if (z.contains(p)) return true;
        return false;
    }

    /** На корабле стоит свой (по правилу) транспондер - в любом режиме. */
    public static boolean shipFriendly(Level level, UUID ship, IffRule rule) {
        Map<BlockPos, Beacon> m = MAP.get(level.dimension());
        if (m == null) return false;
        for (Map.Entry<BlockPos, Beacon> e : m.entrySet()) {
            if (rule.accepts(e.getValue().code()) && ship.equals(SableCompat.containingId(level, e.getKey()))) return true;
        }
        return false;
    }

    public static int count(Level level) {
        Map<BlockPos, Beacon> m = MAP.get(level.dimension());
        return m == null ? 0 : m.size();
    }
}
