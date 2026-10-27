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

    private LinkRegistry() {}

    public static void put(Level level, BlockPos linkPos, List<Member> members) {
        LINKS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(linkPos.immutable(), List.copyOf(members));
    }

    public static void remove(Level level, BlockPos linkPos) {
        Map<BlockPos, List<Member>> m = LINKS.get(level.dimension());
        if (m != null) m.remove(linkPos);
    }

    /** Сети, в которые входит аппаратура. */
    public static Set<UUID> networksOf(Level level, BlockPos equipment) {
        Set<UUID> out = new HashSet<>();
        Map<BlockPos, List<Member>> m = LINKS.get(level.dimension());
        if (m == null) return out;
        for (List<Member> list : m.values()) for (Member mb : list) if (mb.equipment().equals(equipment)) out.add(mb.network());
        return out;
    }

    /** Аппаратура сети (без повторов). */
    public static List<BlockPos> members(Level level, UUID network) {
        Set<BlockPos> out = new LinkedHashSet<>();
        Map<BlockPos, List<Member>> m = LINKS.get(level.dimension());
        if (m == null) return new ArrayList<>();
        for (List<Member> list : m.values()) for (Member mb : list) if (mb.network().equals(network)) out.add(mb.equipment());
        return new ArrayList<>(out);
    }

    /** Аппаратура, связанная с этой хотя бы одной общей сетью (без неё самой). */
    public static List<BlockPos> linkedTo(Level level, BlockPos end) {
        Set<UUID> nets = networksOf(level, end);
        Set<BlockPos> out = new LinkedHashSet<>();
        Map<BlockPos, List<Member>> m = LINKS.get(level.dimension());
        if (m == null || nets.isEmpty()) return new ArrayList<>();
        for (List<Member> list : m.values()) {
            for (Member mb : list) if (nets.contains(mb.network()) && !mb.equipment().equals(end)) out.add(mb.equipment());
        }
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
    }
}
