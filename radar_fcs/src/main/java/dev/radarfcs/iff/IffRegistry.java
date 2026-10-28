package dev.radarfcs.iff;

import dev.radarfcs.compat.ContraptionCompat;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Загруженные транспондеры «свой» (с питанием) по измерениям: код, режим выделения, радиус.
 * Своими их считает только вычислитель, чьё правило (белый или чёрный список кодов) принимает код. Зона своего
 * транспондера бережёт всё внутри - мобов, игроков, транспорт, контрапшены, снаряды, корабли:
 * - «сублевел»: границы корабля Sable / Create Aeronautics (контрапшена Create) с транспондером, с запасом SUBLEVEL_MARGIN;
 * - «радиус»: шар заданного радиуса вокруг транспондера (на корабле - вокруг его текущего положения);
 * - «сублевел и расстояние»: границы корабля, расширенные на заданное расстояние (не на корабле - шар).
 * Корабль (контрапшен) с принятым транспондером свой в любом режиме. Зоны пересчитываются раз в тик.
 * Принадлежность кораблю определяется при каждом пересчёте: при сборке корабля блок может загрузиться
 * в плоте раньше, чем Sable зарегистрирует сам корабль.
 */
public final class IffRegistry {
    /** Запас вокруг границ корабля в режиме «сублевел»: стоящие на палубе и рядом с бортом. */
    public static final double SUBLEVEL_MARGIN = 2.0;

    /** Транспондер: код ("" - без кода), режим (null - по месту: на сублевеле «сублевел», иначе «радиус»), радиус. */
    public record Beacon(String code, @Nullable TransponderMode mode, int radius) {}

    /** Защитная зона в мировых координатах: шар (box == null) или прямоугольник. */
    public record Zone(Vec center, double radius, @Nullable AABB box) {
        public boolean contains(Vec p) {
            if (box != null) return box.contains(p.x(), p.y(), p.z());
            return center.sub(p).lengthSqr() <= radius * radius;
        }
    }

    private record CodedZone(String code, Zone zone) {}

    private record Cached(long tick, List<CodedZone> zones) {}

    private static final Map<ResourceKey<Level>, Map<BlockPos, Beacon>> MAP = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Cached> ZONES = new ConcurrentHashMap<>();

    private IffRegistry() {}

    public static void put(Level level, BlockPos pos, Beacon b) {
        MAP.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(pos.immutable(), b);
        ZONES.remove(level.dimension());
    }

    public static void remove(Level level, BlockPos pos) {
        Map<BlockPos, Beacon> m = MAP.get(level.dimension());
        if (m != null && m.remove(pos) != null) ZONES.remove(level.dimension());
    }

    public static void clear() {
        MAP.clear();
        ZONES.clear();
    }

    /** Режим транспондера с учётом места (не задан - по месту). */
    public static TransponderMode modeAt(Level level, BlockPos pos, @Nullable TransponderMode mode) {
        if (mode != null) return mode;
        return SableCompat.containingId(level, pos) != null ? TransponderMode.SUBLEVEL : TransponderMode.RADIUS;
    }

    /** Зона транспондера: carrier - границы его корабля/контрапшена или null, at - его мировая точка. */
    @Nullable
    static Zone zoneOf(TransponderMode mode, int radius, @Nullable AABB carrier, Vec3 at) {
        return switch (mode) {
            case SUBLEVEL -> carrier == null ? null : new Zone(vec(carrier.getCenter()), 0, carrier.inflate(SUBLEVEL_MARGIN));
            case RADIUS -> new Zone(vec(at), radius, null);
            case SUBLEVEL_AND_DISTANCE -> carrier == null ? new Zone(vec(at), radius, null)
                    : new Zone(vec(carrier.getCenter()), 0, carrier.inflate(radius));
        };
    }

    private static Vec vec(Vec3 v) {
        return new Vec(v.x, v.y, v.z);
    }

    /** Все зоны транспондеров уровня (с кодами), раз в тик. */
    private static List<CodedZone> allZones(Level level) {
        long now = level.getGameTime();
        Cached c = ZONES.get(level.dimension());
        if (c != null && c.tick() == now) return c.zones();
        List<CodedZone> out = new ArrayList<>();
        Map<BlockPos, Beacon> m = MAP.get(level.dimension());
        if (m != null) {
            for (Map.Entry<BlockPos, Beacon> e : m.entrySet()) {
                BlockPos pos = e.getKey();
                Beacon b = e.getValue();
                AABB ship = SableCompat.worldBox(level, pos);
                TransponderMode mode = b.mode() != null ? b.mode() : ship != null ? TransponderMode.SUBLEVEL : TransponderMode.RADIUS;
                Zone z = zoneOf(mode, b.radius(), ship, SableCompat.toWorld(level, Vec3.atCenterOf(pos)));
                if (z != null) out.add(new CodedZone(b.code(), z));
            }
        }
        // транспондеры в движущихся контрапшенах Create (там они без питания: блок контрапшена его не получает)
        if (level instanceof ServerLevel sl) {
            for (Entity ce : ContraptionCompat.withTransponders(sl, now)) {
                for (ContraptionCompat.Beacon b : ContraptionCompat.beacons(ce, now)) {
                    TransponderMode mode = b.mode() != null ? b.mode() : TransponderMode.SUBLEVEL;
                    Zone z = zoneOf(mode, b.radius(), ce.getBoundingBox(), ContraptionCompat.toWorld(ce, b.local()));
                    if (z != null) out.add(new CodedZone(b.code(), z));
                }
            }
        }
        ZONES.put(level.dimension(), new Cached(now, out));
        return out;
    }

    /** Зоны транспондеров, которые правило вычислителя считает своими. */
    public static List<Zone> zones(Level level, IffRule rule) {
        List<Zone> out = new ArrayList<>();
        for (CodedZone cz : allZones(level)) if (rule.accepts(cz.code())) out.add(cz.zone());
        return out;
    }

    /** Точка внутри зоны своего транспондера. */
    public static boolean protectsPoint(Level level, Vec p, IffRule rule) {
        for (CodedZone cz : allZones(level)) if (rule.accepts(cz.code()) && cz.zone().contains(p)) return true;
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
