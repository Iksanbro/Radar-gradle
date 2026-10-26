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
import org.jetbrains.annotations.Nullable;

/**
 * Загруженные транспондеры «свой» по измерениям.
 * Транспондер в обычном мире защищает сферу iffRadius вокруг себя (база).
 * Транспондер на корабле Sable делает своим весь корабль.
 * Транспондеры в контрапшенах Create проверяются по блокам контрапшена (ContraptionCompat).
 */
public final class IffRegistry {
    public record Beacon(BlockPos pos, int channel, @Nullable UUID ship) {}

    private static final Map<ResourceKey<Level>, Map<BlockPos, Beacon>> MAP = new ConcurrentHashMap<>();

    private IffRegistry() {}

    public static void put(Level level, BlockPos pos, int channel) {
        UUID ship = SableCompat.containingId(level, pos);
        MAP.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .put(pos.immutable(), new Beacon(pos.immutable(), channel, ship));
    }

    public static void remove(Level level, BlockPos pos) {
        Map<BlockPos, Beacon> m = MAP.get(level.dimension());
        if (m != null) m.remove(pos);
    }

    public static void clear() { MAP.clear(); }

    /** Центры наземных транспондеров канала. */
    public static List<Vec> groundBeacons(Level level, int channel) {
        List<Vec> out = new ArrayList<>();
        Map<BlockPos, Beacon> m = MAP.get(level.dimension());
        if (m == null) return out;
        for (Beacon b : m.values()) {
            if (b.ship() == null && b.channel() == channel)
                out.add(new Vec(b.pos().getX() + 0.5, b.pos().getY() + 0.5, b.pos().getZ() + 0.5));
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
        Map<BlockPos, Beacon> m = MAP.get(level.dimension());
        if (m == null) return false;
        for (Beacon b : m.values()) if (ship.equals(b.ship()) && b.channel() == channel) return true;
        return false;
    }

    public static int count(Level level) {
        Map<BlockPos, Beacon> m = MAP.get(level.dimension());
        return m == null ? 0 : m.size();
    }
}
