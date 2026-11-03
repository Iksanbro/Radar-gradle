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
 * Радарные сети загруженных линков (как сети складских передатчиков Create): каждый линк на аппаратуре - участник
 * своей сети. Аппаратура одной сети связана вся со всеми, дальность не ограничена. Линк сообщает о себе при загрузке
 * и раз в секунду, снимается при удалении, выгрузке или полном сигнале редстоуна.
 */
public final class LinkRegistry {
    /** Участник сети: аппаратура, на которой стоит линк (у старого парного линка - оба конца). */
    public record Member(BlockPos linkPos, BlockPos equipment, UUID network) {}

    /** linkPos -> участники от этого линка. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, List<Member>>> LINKS = new ConcurrentHashMap<>();
    /** Порядок подключения линков: при переполнении сети работают подключённые раньше. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> ORDER = new ConcurrentHashMap<>();
    private static long sequence;

    /** Линков аппаратуры в сети без блоков памяти и сколько добавляет каждый блок памяти с питанием. */
    public static final int BASE_LINKS = 16, LINKS_PER_MEMORY = 16;

    /** Работающие линки сетей на этот тик (сеть -> линки) - пересчёт раз в тик. */
    private static final Map<ResourceKey<Level>, Map<UUID, Set<BlockPos>>> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Long> ACTIVE_TICK = new ConcurrentHashMap<>();

    private LinkRegistry() {}

    public static void put(Level level, BlockPos linkPos, List<Member> members) {
        BlockPos p = linkPos.immutable();
        LINKS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(p, List.copyOf(members));
        ORDER.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).computeIfAbsent(p, k -> nextSequence());
        ACTIVE_TICK.remove(level.dimension());
    }

    private static synchronized long nextSequence() {
        return sequence++;
    }

    public static void remove(Level level, BlockPos linkPos) {
        Map<BlockPos, List<Member>> m = LINKS.get(level.dimension());
        if (m != null) m.remove(linkPos);
        Map<BlockPos, Long> o = ORDER.get(level.dimension());
        if (o != null) o.remove(linkPos);
        ACTIVE_TICK.remove(level.dimension());
    }

    /**
     * Работающие линки сети: линки блоков памяти - всегда; остальной аппаратуры - не больше BASE_LINKS +
     * LINKS_PER_MEMORY за каждый блок памяти сети с питанием, по порядку подключения.
     */
    private static Set<BlockPos> active(Level level, UUID network) {
        long now = level.getGameTime();
        Long at = ACTIVE_TICK.get(level.dimension());
        Map<UUID, Set<BlockPos>> cache = ACTIVE.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
        if (at == null || at != now) {
            cache.clear();
            ACTIVE_TICK.put(level.dimension(), now);
        }
        return cache.computeIfAbsent(network, n -> computeActive(level, n));
    }

    private static Set<BlockPos> computeActive(Level level, UUID network) {
        Map<BlockPos, List<Member>> m = LINKS.get(level.dimension());
        Set<BlockPos> out = new HashSet<>();
        if (m == null) return out;
        Map<BlockPos, Long> order = ORDER.getOrDefault(level.dimension(), Map.of());
        List<BlockPos> others = new ArrayList<>();
        Set<BlockPos> memories = new HashSet<>();
        for (Map.Entry<BlockPos, List<Member>> e : m.entrySet()) {
            boolean inNet = false, memory = false;
            for (Member mb : e.getValue()) {
                if (!mb.network().equals(network)) continue;
                inNet = true;
                if (isMemory(level, mb.equipment())) {
                    memory = true;
                    if (poweredMemory(level, mb.equipment())) memories.add(mb.equipment());
                }
            }
            if (!inNet) continue;
            if (memory) out.add(e.getKey());
            else others.add(e.getKey());
        }
        others.sort(java.util.Comparator.<BlockPos>comparingLong(p -> order.getOrDefault(p, Long.MAX_VALUE)).thenComparingLong(BlockPos::asLong));
        int cap = capacityFor(memories.size());
        for (int i = 0; i < Math.min(cap, others.size()); i++) out.add(others.get(i));
        return out;
    }

    public static int capacityFor(int memories) {
        return BASE_LINKS + LINKS_PER_MEMORY * memories;
    }

    private static boolean isMemory(Level level, BlockPos p) {
        return level.isLoaded(p) && level.getBlockEntity(p) instanceof dev.radarfcs.block.MemoryBlockEntity;
    }

    private static boolean poweredMemory(Level level, BlockPos p) {
        return level.isLoaded(p) && level.getBlockEntity(p) instanceof dev.radarfcs.block.MemoryBlockEntity mem && mem.powered();
    }

    /** Работает ли линк (сеть не переполнена или он в пределах ёмкости). */
    public static boolean isActive(Level level, BlockPos linkPos) {
        Map<BlockPos, List<Member>> m = LINKS.get(level.dimension());
        List<Member> list = m == null ? null : m.get(linkPos);
        if (list == null || list.isEmpty()) return false;
        return active(level, list.get(0).network()).contains(linkPos);
    }

    /** Сколько линков аппаратуры (без блоков памяти) в сети и сколько может работать: {занято, ёмкость}. */
    public static int[] usage(Level level, UUID network) {
        Map<BlockPos, List<Member>> m = LINKS.get(level.dimension());
        int used = 0;
        Set<BlockPos> memories = new HashSet<>();
        if (m != null) {
            for (List<Member> list : m.values()) {
                boolean inNet = false, memory = false;
                for (Member mb : list) {
                    if (!mb.network().equals(network)) continue;
                    inNet = true;
                    if (isMemory(level, mb.equipment())) {
                        memory = true;
                        if (poweredMemory(level, mb.equipment())) memories.add(mb.equipment());
                    }
                }
                if (inNet && !memory) used++;
            }
        }
        return new int[]{used, capacityFor(memories.size())};
    }

    /** Сети, в которые входит аппаратура (только через работающие линки). */
    public static Set<UUID> networksOf(Level level, BlockPos equipment) {
        Set<UUID> out = new HashSet<>();
        Map<BlockPos, List<Member>> m = LINKS.get(level.dimension());
        if (m == null) return out;
        for (Map.Entry<BlockPos, List<Member>> e : m.entrySet()) {
            for (Member mb : e.getValue()) {
                if (mb.equipment().equals(equipment) && active(level, mb.network()).contains(e.getKey())) out.add(mb.network());
            }
        }
        return out;
    }

    /** Аппаратура сети (без повторов, только через работающие линки). */
    public static List<BlockPos> members(Level level, UUID network) {
        Set<BlockPos> out = new LinkedHashSet<>();
        Map<BlockPos, List<Member>> m = LINKS.get(level.dimension());
        if (m == null) return new ArrayList<>();
        Set<BlockPos> act = active(level, network);
        for (Map.Entry<BlockPos, List<Member>> e : m.entrySet()) {
            if (!act.contains(e.getKey())) continue;
            for (Member mb : e.getValue()) if (mb.network().equals(network)) out.add(mb.equipment());
        }
        return new ArrayList<>(out);
    }

    /** Аппаратура, связанная с этой хотя бы одной общей сетью (без неё самой). */
    public static List<BlockPos> linkedTo(Level level, BlockPos end) {
        Set<UUID> nets = networksOf(level, end);
        Set<BlockPos> out = new LinkedHashSet<>();
        if (nets.isEmpty()) return new ArrayList<>();
        for (UUID n : nets) for (BlockPos p : members(level, n)) if (!p.equals(end)) out.add(p);
        return new ArrayList<>(out);
    }

    /** Две единицы аппаратуры в одной сети. */
    public static boolean linked(Level level, BlockPos a, BlockPos b) {
        Set<UUID> na = networksOf(level, a);
        na.retainAll(networksOf(level, b));
        return !na.isEmpty();
    }

    public static void clear() {
        LINKS.clear();
        ORDER.clear();
        ACTIVE.clear();
        ACTIVE_TICK.clear();
    }
}
