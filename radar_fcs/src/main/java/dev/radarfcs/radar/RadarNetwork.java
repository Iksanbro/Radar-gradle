package dev.radarfcs.radar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Сеть радаров по каналам: вычислители и мониторы получают отметки всех работающих радаров своего канала
 * в радиусе networkRange. Здесь же - назначение цели с монитора и цели, по которым ведётся огонь.
 */
public final class RadarNetwork {
    private record Key(ResourceKey<Level> dim, int channel) {}

    private record Designation(UUID target, long until) {}

    private static final Map<ResourceKey<Level>, Set<RadarEmitter>> RADARS = new ConcurrentHashMap<>();
    private static final Map<Key, Designation> DESIGNATIONS = new ConcurrentHashMap<>();
    private static final Map<Key, Map<UUID, Long>> ENGAGED = new ConcurrentHashMap<>();

    private RadarNetwork() {}

    /** Радар сообщает о себе каждый тик работы; выгруженные и снятые отсеиваются при запросе. */
    public static void register(RadarEmitter r) {
        RADARS.computeIfAbsent(r.radarLevel().dimension(), k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(r);
    }

    public static void unregister(RadarEmitter r) {
        Level level = r.radarLevel();
        if (level == null) return;
        Set<RadarEmitter> set = RADARS.get(level.dimension());
        if (set != null) set.remove(r);
    }

    /** Работающие радары канала, чья антенна не дальше range от точки. */
    public static List<RadarEmitter> linked(Level level, Vec3 at, int channel, double range) {
        List<RadarEmitter> out = new ArrayList<>();
        Set<RadarEmitter> set = RADARS.get(level.dimension());
        if (set == null) return out;
        double r2 = range * range;
        for (RadarEmitter r : set) {
            if (r.isRadarRemoved() || r.radarLevel() != level) {
                set.remove(r);
                continue;
            }
            if (r.radarChannel() != channel || !r.radarRunning()) continue;
            if (r.radarOrigin().distanceToSqr(at) <= r2) out.add(r);
        }
        return out;
    }

    // ------------------------------------------------------- назначение цели

    public static void designate(Level level, int channel, UUID target, long until) {
        DESIGNATIONS.put(new Key(level.dimension(), channel), new Designation(target, until));
    }

    public static void clearDesignation(Level level, int channel) {
        DESIGNATIONS.remove(new Key(level.dimension(), channel));
    }

    @Nullable
    public static UUID designated(Level level, int channel, long now) {
        Key k = new Key(level.dimension(), channel);
        Designation d = DESIGNATIONS.get(k);
        if (d == null) return null;
        if (d.until() < now) {
            DESIGNATIONS.remove(k);
            return null;
        }
        return d.target();
    }

    // ------------------------------------------------ цели под огнём (для мониторов)

    public static void markEngaged(Level level, int channel, UUID target, long until) {
        ENGAGED.computeIfAbsent(new Key(level.dimension(), channel), k -> new ConcurrentHashMap<>()).put(target, until);
    }

    public static boolean isEngaged(Level level, int channel, UUID target, long now) {
        Map<UUID, Long> m = ENGAGED.get(new Key(level.dimension(), channel));
        if (m == null) return false;
        Long until = m.get(target);
        if (until == null) return false;
        if (until < now) {
            m.remove(target);
            return false;
        }
        return true;
    }

    public static void clear() {
        RADARS.clear();
        DESIGNATIONS.clear();
        ENGAGED.clear();
    }
}
