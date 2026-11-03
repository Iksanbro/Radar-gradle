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

    private GunLinks() {}

    public static void put(Level level, BlockPos linkPos, BlockPos equipment, UUID network) {
        LINKS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .put(linkPos.immutable(), new Entry(equipment.immutable(), network));
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
    }
}
