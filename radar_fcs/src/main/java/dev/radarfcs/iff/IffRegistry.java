package dev.radarfcs.iff;

import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Vec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Загруженные транспондеры «свой» по измерениям.
 * Транспондер в обычном мире защищает сферу iffRadius вокруг себя (база).
 * Транспондер на корабле Sable / Create Aeronautics делает своим весь корабль.
 * Транспондеры в контрапшенах Create проверяются по блокам контрапшена (ContraptionCompat).
 * Принадлежность кораблю определяется при каждом запросе: при сборке корабля блок может загрузиться
 * в плоте раньше, чем Sable зарегистрирует сам корабль.
 */
public final class IffRegistry {
    private static final Map<ResourceKey<Level>, Map<BlockPos, Integer>> MAP = new ConcurrentHashMap<>();

    private IffRegistry() {}

    public static void put(Level level, BlockPos pos, int channel) {
        MAP.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(pos.immutable(), channel);
    }

    public static void remove(Level level, BlockPos pos) {
        Map<BlockPos, Integer> m = MAP.get(level.dimension());
        if (m != null) m.remove(pos);
    }

    public static void clear() { MAP.clear(); }

    /** Центры наземных транспондеров канала. */
    public static List<Vec> groundBeacons(Level level, int channel) {
        List<Vec> out = new ArrayList<>();
        Map<BlockPos, Integer> m = MAP.get(level.dimension());
        if (m == null) return out;
        for (Map.Entry<BlockPos, Integer> e : m.entrySet()) {
            if (e.getValue() != channel) continue;
            BlockPos p = e.getKey();
            if (SableCompat.containingId(level, p) == null) out.add(new Vec(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
        }
        return out;
    }

    /** Точка внутри защитной сферы наземного транспондера. */
    public static boolean protectsPoint(Level level, Vec p, int channel, double radius) {
        double r2 = radius * radius;
        for (Vec b : groundBeacons(level, channel)) if (b.sub(p).lengthSqr() <= r2) return true;
        return false;
    }

    /** На корабле стоит транспондер этого канала. */
    public static boolean shipFriendly(Level level, UUID ship, int channel) {
        Map<BlockPos, Integer> m = MAP.get(level.dimension());
        if (m == null) return false;
        for (Map.Entry<BlockPos, Integer> e : m.entrySet()) {
            if (e.getValue() == channel && ship.equals(SableCompat.containingId(level, e.getKey()))) return true;
        }
        return false;
    }

    public static int count(Level level) {
        Map<BlockPos, Integer> m = MAP.get(level.dimension());
        return m == null ? 0 : m.size();
    }
}
