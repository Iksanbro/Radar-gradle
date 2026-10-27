package dev.radarfcs.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Связи радарных линков загруженных блоков: линк сообщает о себе при загрузке и снимается при удалении или выгрузке.
 * Связь неориентированная: концы - позиции аппаратуры (вычислитель, станок, радар, блок монитора).
 */
public final class LinkRegistry {
    /** Связь: где стоит линк и какие блоки он соединяет. */
    public record Link(BlockPos linkPos, BlockPos a, BlockPos b) {
        public boolean touches(BlockPos p) {
            return a.equals(p) || b.equals(p);
        }

        public BlockPos otherEnd(BlockPos p) {
            return a.equals(p) ? b : a;
        }
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, Link>> LINKS = new ConcurrentHashMap<>();

    private LinkRegistry() {}

    public static void put(Level level, BlockPos linkPos, BlockPos a, BlockPos b) {
        LINKS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .put(linkPos.immutable(), new Link(linkPos.immutable(), a.immutable(), b.immutable()));
    }

    public static void remove(Level level, BlockPos linkPos) {
        Map<BlockPos, Link> m = LINKS.get(level.dimension());
        if (m != null) m.remove(linkPos);
    }

    /** Позиции, связанные линками с этой. */
    public static List<BlockPos> linkedTo(Level level, BlockPos end) {
        List<BlockPos> out = new ArrayList<>();
        Map<BlockPos, Link> m = LINKS.get(level.dimension());
        if (m == null) return out;
        for (Link l : m.values()) if (l.touches(end)) out.add(l.otherEnd(end));
        return out;
    }

    /** Уже есть линк между этими двумя блоками. */
    public static boolean linked(Level level, BlockPos a, BlockPos b) {
        Map<BlockPos, Link> m = LINKS.get(level.dimension());
        if (m == null) return false;
        for (Link l : m.values()) if (l.touches(a) && l.otherEnd(a).equals(b)) return true;
        return false;
    }

    public static void clear() {
        LINKS.clear();
    }
}
