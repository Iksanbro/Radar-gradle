package dev.radarfcs.link;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Орудийные сети: орудийный линк на части орудия (прицел, вращатель, станок CBC) объединяет их в одно орудие.
 * Отдельно от радарных сетей и без лимита подключений: орудие - это несколько блоков одной постройки.
 */
public final class GunLinks {
    /** linkPos -> (аппаратура, сеть). */
    private record Entry(BlockPos equipment, UUID network) {}

    private static final Map<ResourceKey<Level>, Map<BlockPos, Entry>> LINKS = new ConcurrentHashMap<>();
    /** Настройки орудия по сетям: самые новые из линков сети. */
    private static final Map<ResourceKey<Level>, Map<UUID, dev.radarfcs.fcs.GunSettings>> SETTINGS = new ConcurrentHashMap<>();

    private GunLinks() {}

    public static void put(Level level, BlockPos linkPos, BlockPos equipment, UUID network, dev.radarfcs.fcs.GunSettings settings) {
        LINKS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .put(linkPos.immutable(), new Entry(equipment.immutable(), network));
        SETTINGS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .merge(network, settings, (a, b) -> b.stamp >= a.stamp ? b : a);
    }

    /** Настройки орудия сети (без настроенных линков - по умолчанию). */
    public static dev.radarfcs.fcs.GunSettings settings(Level level, UUID network) {
        Map<UUID, dev.radarfcs.fcs.GunSettings> m = SETTINGS.get(level.dimension());
        dev.radarfcs.fcs.GunSettings g = m == null ? null : m.get(network);
        return g == null ? dev.radarfcs.fcs.GunSettings.DEFAULT : g;
    }

    /** Настройки всего орудия, в которое входит аппаратура: самые новые из его сетей. */
    public static dev.radarfcs.fcs.GunSettings settingsOf(Level level, java.util.Collection<UUID> networks) {
        dev.radarfcs.fcs.GunSettings best = dev.radarfcs.fcs.GunSettings.DEFAULT;
        for (UUID n : networks) {
            dev.radarfcs.fcs.GunSettings g = settings(level, n);
            if (g.stamp > best.stamp) best = g;
        }
        return best;
    }

    /** Линки сети (позиции) - чтобы раздать им новые настройки. */
    public static List<BlockPos> links(Level level, UUID network) {
        List<BlockPos> out = new ArrayList<>();
        Map<BlockPos, Entry> m = LINKS.get(level.dimension());
        if (m != null) for (Map.Entry<BlockPos, Entry> e : m.entrySet()) if (e.getValue().network().equals(network)) out.add(e.getKey());
        return out;
    }

    /** Сети всего орудия (по цепочке сетей), в которое входит аппаратура. */
    public static Set<UUID> groupNetworks(Level level, BlockPos equipment) {
        Set<UUID> seen = new HashSet<>();
        for (BlockPos p : group(level, equipment)) seen.addAll(networksOf(level, p));
        return seen;
    }

    public static void remove(Level level, BlockPos linkPos) {
        Map<BlockPos, Entry> m = LINKS.get(level.dimension());
        if (m != null) m.remove(linkPos);
    }

    /** Орудийные сети, в которые входит аппаратура. */
    public static Set<UUID> networksOf(Level level, BlockPos equipment) {
        Set<UUID> out = new HashSet<>();
        Map<BlockPos, Entry> m = LINKS.get(level.dimension());
        if (m == null) return out;
        for (Entry e : m.values()) if (e.equipment().equals(equipment)) out.add(e.network());
        return out;
    }

    /** Аппаратура сети без повторов. */
    public static List<BlockPos> members(Level level, UUID network) {
        Set<BlockPos> out = new LinkedHashSet<>();
        Map<BlockPos, Entry> m = LINKS.get(level.dimension());
        if (m != null) for (Entry e : m.values()) if (e.network().equals(network)) out.add(e.equipment());
        return new ArrayList<>(out);
    }

    /** Всё орудие, в которое входит аппаратура: она сама и всё из её орудийных сетей (по цепочке сетей). */
    public static List<BlockPos> group(Level level, BlockPos equipment) {
        Set<BlockPos> out = new LinkedHashSet<>();
        Set<UUID> seen = new HashSet<>();
        List<BlockPos> queue = new ArrayList<>();
        queue.add(equipment);
        out.add(equipment);
        for (int i = 0; i < queue.size() && i < 256; i++) {
            for (UUID n : networksOf(level, queue.get(i))) {
                if (!seen.add(n)) continue;
                for (BlockPos p : members(level, n)) if (out.add(p)) queue.add(p);
            }
        }
        return new ArrayList<>(out);
    }

    public static void clear() {
        LINKS.clear();
        SETTINGS.clear();
    }
}
